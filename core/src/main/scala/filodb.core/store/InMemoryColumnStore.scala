package filodb.core.store

import bloomfilter.mutable.BloomFilter
import com.typesafe.scalalogging.slf4j.StrictLogging
import java.nio.ByteBuffer
import java.util.concurrent.{ConcurrentHashMap, ConcurrentSkipListMap, ConcurrentNavigableMap}
import javax.xml.bind.DatatypeConverter
import monix.reactive.Observable
import scala.collection.mutable.HashMap
import scala.concurrent.{ExecutionContext, Future}
import scalaxy.loops._

import filodb.core._
import filodb.core.binaryrecord.BinaryRecord
import filodb.core.metadata.{Column, Projection, RichProjection}
import filodb.core.query.{PartitionChunkIndex, MutablePartitionChunkIndex, ChunkIDPartitionChunkIndex}
import filodb.core.Types._

/**
 * A ColumnStore implementation which is entirely in memory for speed.
 * Good for testing or performance.
 *
 * NOTE: This implementation effectively only works on a single node.
 * We would need, for example, a Spark-specific implementation which can
 * know how to distribute data, or at least keep track of different nodes,
 * TODO: use thread-safe structures
 */
class InMemoryColumnStore(val readEc: ExecutionContext)(implicit val ec: ExecutionContext)
extends ColumnStore with InMemoryColumnStoreScanner with StrictLogging {
  import Types._
  import collection.JavaConversions._

  logger.info("Starting InMemoryColumnStore...")

  val chunkDb = new HashMap[(DatasetRef, PartitionKey, Int), InMemoryChunkStore]
  val indices = new HashMap[(DatasetRef, PartitionKey, Int), MutablePartitionChunkIndex]
  val filters = new HashMap[(DatasetRef, PartitionKey, Int), FilterTree]

  def initializeProjection(projection: Projection): Future[Response] = Future.successful(Success)

  def clearProjectionData(projection: Projection): Future[Response] = Future {
    chunkDb.keys.collect { case key @ (ds, _, _) if ds == projection.dataset => chunkDb remove key }
    indices.keys.collect { case key @ (ds, _, _) if ds == projection.dataset => indices remove key }
    filters.keys.collect { case key @ (ds, _, _) if ds == projection.dataset => filters remove key }
    Success
  }

  def dropDataset(dataset: DatasetRef): Future[Response] = {
    chunkDb.synchronized {
      chunkDb.retain { case ((ds, _, _), _) => ds != dataset }
    }
    indices.synchronized {
      indices.retain { case ((ds, _, _), _) => ds != dataset }
    }
    filters.synchronized {
      filters.retain { case ((ds, _, _), _) => ds != dataset }
    }
    Future.successful(Success)
  }

  def appendSegment(projection: RichProjection,
                    segment: ChunkSetSegment,
                    version: Int): Future[Response] = Future {
    val dbKey = (projection.datasetRef, segment.partition, version)

    if (segment.chunkSets.isEmpty) { NotApplied }
    else {
      // Add chunks
      val chunkStore = chunkDb.synchronized {
        chunkDb.getOrElseUpdate(dbKey, new InMemoryChunkStore)
      }
      for { chunkSet <- segment.chunkSets } {
        chunkStore.addChunkMap(chunkSet.info.id, chunkSet.chunks)
      }

      // Add chunk infos, skips, and filter
      val partIndex = indices.synchronized {
        indices.getOrElseUpdate(dbKey, new ChunkIDPartitionChunkIndex(segment.partition,
                                                                     projection))
      }
      val filterTree = filters.synchronized {
        filters.getOrElseUpdate(dbKey, new FilterTree)
      }

      for { chunkSet <- segment.chunkSets } {
        partIndex.add(chunkSet.info, chunkSet.skips)
        filterTree.put(chunkSet.info.id, chunkSet.bloomFilter)
      }

      Success
    }
  }

  def shutdown(): Unit = {}

  def reset(): Unit = {
    chunkDb.clear()
    indices.clear()
    filters.clear()
  }

  // InMemoryColumnStore is just on one node, so return no splits for now.
  // TODO: achieve parallelism by splitting on a range of partitions.
  def getScanSplits(dataset: DatasetRef, splitsPerNode: Int): Seq[ScanSplit] =
    Seq(InMemoryWholeSplit)

  def bbToHex(bb: ByteBuffer): String = DatatypeConverter.printHexBinary(bb.array)
}

// TODO(velvia): Implement real splits?
case object InMemoryWholeSplit extends ScanSplit {
  def hostnames: Set[String] = Set.empty
}

trait InMemoryColumnStoreScanner extends ColumnStoreScanner {
  import Types._
  import collection.JavaConversions._

  type FilterTree = ConcurrentSkipListMap[ChunkID, BloomFilter[Long]]
  val EmptyFilterTree = new FilterTree

  def chunkDb: HashMap[(DatasetRef, PartitionKey, Int), InMemoryChunkStore]
  def indices: HashMap[(DatasetRef, PartitionKey, Int), MutablePartitionChunkIndex]
  def filters: HashMap[(DatasetRef, PartitionKey, Int), FilterTree]

  def readPartitionChunks(dataset: DatasetRef,
                          version: Int,
                          columns: Seq[Column],
                          partitionIndex: PartitionChunkIndex,
                          chunkMethod: ChunkScanMethod): Observable[ChunkPipeItem] = {
    chunkDb.get((dataset, partitionIndex.binPartition, version)).map { chunkStore =>
      logger.debug(s"Reading chunks from columns $columns, ${partitionIndex.binPartition}, method $chunkMethod")
      val infosSkips = partitionIndex.findByMethod(chunkMethod).toBuffer
      val colIndex = columns.map { col => chunkStore.columnMap.getOrElse(col.name, Int.MaxValue) }.toArray

      val infoStream = Observable.now(ChunkPipeInfos(infosSkips))

      infoStream ++ Observable.fromIterable(infosSkips).flatMap { case (info, skips) =>
        val chunks = (0 until colIndex.size).map { i =>
          SingleChunkInfo(info.id, i, chunkStore.getChunk(info.id, colIndex(i)))
        }
        Observable.fromIterable(chunks)
      }
    }.getOrElse(Observable.empty)
  }

  def readFilters(dataset: DatasetRef,
                  version: Int,
                  partition: Types.PartitionKey,
                  chunkRange: (Types.ChunkID, Types.ChunkID))
                 (implicit ec: ExecutionContext): Future[Iterator[SegmentState.IDAndFilter]] = {
    val filterTree = filters.getOrElse((dataset, partition, version), EmptyFilterTree)
    val it = filterTree.subMap(chunkRange._1, true, chunkRange._2, true).entrySet.iterator.map { entry =>
      (entry.getKey, entry.getValue)
    }
    Future.successful(it)
  }

  def singlePartScan(projection: RichProjection, version: Int, partition: PartitionKey):
    Iterator[PartitionChunkIndex] = {
    indices.get((projection.datasetRef, partition, version)).toIterator
  }

  def multiPartScan(projection: RichProjection, version: Int, partitions: Seq[PartitionKey]):
    Iterator[PartitionChunkIndex] = {
    partitions.flatMap { partition =>
      indices.get((projection.datasetRef, partition, version)).toSeq
    }.toIterator
  }

  def filteredPartScan(projection: RichProjection,
                       version: Int,
                       split: ScanSplit,
                       filterFunc: PartitionKey => Boolean): Iterator[PartitionChunkIndex] = {
    val partitions = indices.keysIterator.collect { case (ds, partition, ver) if
      ds == projection.datasetRef && ver == version => partition }
    partitions.filter(filterFunc)
              .map { partition => indices((projection.datasetRef, partition, version)) }
  }

  def scanPartitions(projection: RichProjection,
                     version: Int,
                     partMethod: PartitionScanMethod): Observable[PartitionChunkIndex] = {
    val indexIt = partMethod match {
      case SinglePartitionScan(partition) => singlePartScan(projection, version, partition)
      case MultiPartitionScan(partitions) => multiPartScan(projection, version, partitions)
      case FilteredPartitionScan(split, filterFunc) =>
        filteredPartScan(projection, version, split, filterFunc)
    }
    Observable.fromIterator(indexIt)
  }
}

class InMemoryChunkStore extends StrictLogging {
  val columnMap = new HashMap[ColumnId, Int]
  var highestColumnNo = 0
  val chunkStore = new ConcurrentHashMap[ChunkID, Array[ByteBuffer]]

  private def assignColNo(column: ColumnId): Int = {
    val colNo = columnMap.getOrElseUpdate(column, columnMap.size)
    highestColumnNo = Math.max(highestColumnNo, colNo)
    colNo
  }

  def addChunkMap(id: ChunkID, chunks: Map[ColumnId, ByteBuffer]): Unit = synchronized {
    val indexAndBytes = chunks.map { case (colName, bytes) => (assignColNo(colName), bytes) }
    val chunkArray = new Array[ByteBuffer](highestColumnNo + 1)
    indexAndBytes.foreach { case (idx, bytes) => chunkArray(idx) = bytes }
    chunkStore.put(id, chunkArray)
  }

  def getChunk(chunkId: ChunkID, colNo: Int): ByteBuffer = {
    //scalastyle:off
    chunkStore.get(chunkId) match {
      case null  =>
        null
      case chunks: Array[ByteBuffer] =>
        if (colNo >= chunks.size) null else chunks(colNo)
    }
    //scalastyle:on
  }
}