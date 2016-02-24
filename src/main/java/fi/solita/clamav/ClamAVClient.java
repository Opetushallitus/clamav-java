package fi.solita.clamav;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Simple client for ClamAV's clamd scanner. Provides straightforward instream scanning.
 */
public class ClamAVClient {

  private String hostName;
  private int port;
  private int timeout;

  // "do not exceed StreamMaxLength as defined in clamd.conf, otherwise clamd will reply with INSTREAM size limit exceeded and close the connection."
  private static final int CHUNK_SIZE = 2048;
  private static final int DEFAULT_TIMEOUT = 500;

  /**
   * @param timeout zero means infinite timeout. Not a good idea, but will be accepted.
   */
  public ClamAVClient(String hostName, int port, int timeout)  {
    if (timeout < 0) {
      throw new IllegalArgumentException("Negative timeout value does not make sense.");
    }
    this.hostName = hostName;
    this.port = port;
    this.timeout = timeout;
  }

  public ClamAVClient(String hostName, int port) {
    this(hostName, port, DEFAULT_TIMEOUT);
  }

  /**
   * Run PING command to clamd to test it is responding.
   * 
   * @return true if the server responded with proper ping reply.
   */
  public boolean ping() throws IOException {
    try (Socket s = new Socket(hostName,port); OutputStream outs = s.getOutputStream()) {
      s.setSoTimeout(timeout);
      outs.write(asBytes("zPING\0"));
      outs.flush();
      byte[] b = new byte[4];
      s.getInputStream().read(b);
      return Arrays.equals(b, asBytes("PONG"));
    }
  }

  /**
   * Streams the given data to the server in chunks. The whole data is not kept in memory.
   * <p>
   * Opens a socket and reads the reply. Parameter input stream is NOT closed. 
   * 
   * @param is data to scan. Not closed by this method!
   * @return server reply
   */
  public byte[] scan(InputStream is) throws IOException {
    try (Socket s = new Socket(hostName,port); OutputStream outs = new BufferedOutputStream(s.getOutputStream())) {
      s.setSoTimeout(timeout); 

      // handshake
      outs.write(asBytes("zINSTREAM\0"));
      outs.flush();
      byte[] chunk = new byte[CHUNK_SIZE];

      // send data
      int read = is.read(chunk);
      while (read >= 0) {
        // The format of the chunk is: '<length><data>' where <length> is the size of the following data in bytes expressed as a 4 byte unsigned
        // integer in network byte order and <data> is the actual chunk. Streaming is terminated by sending a zero-length chunk.
        byte[] chunkSize = ByteBuffer.allocate(4).putInt(read).array();
        outs.write(chunkSize);
        outs.write(chunk, 0, read);
        read = is.read(chunk);
      }

      // terminate scan
      outs.write(new byte[]{0,0,0,0});
      outs.flush();

      // read reply
      try (InputStream clamIs = s.getInputStream()) {
        return readAll(clamIs);
      }
    } 
  }

  /**
   * @param in data to scan
   * @return server reply
   **/
  public byte[] scan(byte[] in) throws IOException {
    ByteArrayInputStream bis = new ByteArrayInputStream(in);
    return scan(bis);
  }

  /**
   * @return true if no virus was found according to the clamd reply message
   */
  public static boolean isCleanReply(byte[] reply) throws UnsupportedEncodingException {
    String r = new String(reply, "ASCII");
    return (r.contains("OK") && !r.contains("FOUND"));
  }

  // byte conversion based on ASCII character set regardless of the current system locale
  private static byte[] asBytes(String s) throws UnsupportedEncodingException {
    return s.getBytes("ASCII");
  }

  // reads all available bytes from the stream
  private static byte[] readAll(InputStream is) throws IOException {
    ByteArrayOutputStream tmp = new ByteArrayOutputStream();

    byte[] buf = new byte[2000];
    int read = is.read(buf);
    while (read > 0) {
      tmp.write(buf, 0, read);
      read = is.read(buf);
    }
    return tmp.toByteArray();
  }
}
