package jsonrpclib.fs2interop.internals

import cats.MonadThrow
import cats.effect.Concurrent
import cats.effect.std.Queue
import cats.implicits._
import fs2.Chunk
import fs2.Stream
import jsonrpclib.Payload

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

object LSP {

  def writeSink[F[_]: Concurrent](
      writePipe: fs2.Pipe[F, Byte, Nothing],
      bufferSize: Int
  ): Stream[F, Payload => F[Unit]] =
    Stream.eval(Queue.bounded[F, Payload](bufferSize)).flatMap { queue =>
      val payloads = fs2.Stream.fromQueueUnterminated(queue, bufferSize)
      Stream(queue.offer(_)).concurrently(payloads.map(writeChunk).flatMap(Stream.chunk(_)).through(writePipe))
    }

  /** Split a stream of bytes into payloads by extracting each frame based on information contained in the headers.
    *
    * See https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#contentPart
    */
  def readStream[F[_]: MonadThrow](bytes: Stream[F, Byte]): Stream[F, Payload] =
    bytes
      .scanChunks(ScanState.starting) { case (state, chunk) =>
        val (ns, maybeResult) = loop(state.concatChunk(chunk))
        (ns, Chunk(maybeResult))
      }
      .flatMap {
        case Right(acc)  => Stream.iterable(acc).map(c => Payload(c.toArray))
        case Left(error) => Stream.raiseError[F](error)
      }

  private def writeChunk(payload: Payload): Chunk[Byte] = {
    val size = payload.array.size
    val header = s"Content-Length: ${size}" + "\r\n" * 2
    Chunk.array(header.getBytes()) ++ Chunk.array(payload.array)
  }

  private val returnByte = '\r'.toByte
  private val newlineByte = '\n'.toByte

  private final case class LSPHeaders(
      contentLength: Int,
      mimeType: String,
      charset: Charset
  )

  private final case class ParseError(message: String) extends Throwable {
    override def getMessage(): String = message
  }

  private def parseHeader(
      line: String,
      headers: LSPHeaders
  ): Either[ParseError, LSPHeaders] =
    line.trim() match {
      case s"Content-Length: ${integer(length)}" =>
        Right(headers.copy(contentLength = length))
      case s"Content-type: ${mimeType}; charset=${charset}" =>
        Right(
          headers.copy(mimeType = mimeType, charset = Charset.forName(charset))
        )
      case _ => Left(ParseError(s"Couldn't parse to header: $line"))
    }

  private object integer {
    def unapply(string: String): Option[Int] = string.toIntOption
  }

  private final case class ScanState(status: Status, currentHeaders: LSPHeaders, buffered: Chunk[Byte]) {
    def concatChunk(other: Chunk[Byte]) = copy(buffered = buffered ++ other)
  }

  private object ScanState {
    def readingHeader(storedChunk: Chunk[Byte]) = ScanState(
      Status.ReadingHeader,
      LSPHeaders(-1, "application/json", StandardCharsets.UTF_8),
      storedChunk
    )

    val starting: ScanState = readingHeader(Chunk.empty)
  }

  private sealed trait Status

  private object Status {
    case object ReadingHeader extends Status
    case object FinishedReadingHeader extends Status
    case object ReadingBody extends Status
  }

  private def loop(
      state: ScanState,
      acc: Seq[Chunk[Byte]] = Seq.empty
  ): (ScanState, Either[ParseError, Seq[Chunk[Byte]]]) =
    state match {
      case ScanState(Status.ReadingBody, headers, buffered) =>
        if (headers.contentLength <= buffered.size) {
          // We have a full payload to emit
          val (payload, tail) = buffered.splitAt(headers.contentLength)
          val newState = ScanState.readingHeader(tail)
          loop(newState, acc.appended(payload))
        } else {
          (state, Right(acc))
        }
      case ScanState(Status.ReadingHeader, headers, buffered) =>
        val bb = java.nio.ByteBuffer.allocate(buffered.size)
        val iterator = buffered.iterator
        var continue = true
        var newState: ScanState = null
        var error: ParseError = null
        while (iterator.hasNext && continue) {
          val byte = iterator.next()
          if (byte == newlineByte) {
            parseHeader(new String(bb.array, StandardCharsets.US_ASCII), headers) match {
              case Right(newHeader) =>
                newState = ScanState(Status.FinishedReadingHeader, newHeader, Chunk.iterator(iterator))
              case Left(e) =>
                error = e
            }
            continue = false
          } else {
            bb.put(byte)
          }
        }
        if (newState != null) {
          loop(newState, acc)
        } else if (error != null) {
          (state, Left(error))
        } else {
          (state, Right(acc))
        }

      case ScanState(Status.FinishedReadingHeader, headers, buffered) =>
        if (buffered.size >= 2) {
          if (buffered.startsWith(Seq(returnByte, newlineByte))) {
            // We have read two `\r\n` in a row, starting to scan a body
            loop(ScanState(Status.ReadingBody, headers, buffered.drop(2)), acc)
          } else {
            loop(ScanState(Status.ReadingHeader, headers, buffered), acc)
          }
        } else {
          (state, Right(acc))
        }
    }

}
