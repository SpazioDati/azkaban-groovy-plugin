package eu.spaziodati.azkaban.jobtype;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;

// 99% taken from https://community.oracle.com/thread/1164250

/**
 * Stream that flushes data to a Log4j Logger: once a newline
 * is received a new log message is sent
 * The class is not thread-safe
 */
public class StreamToLogger extends OutputStream {

    ///Logger that we log to
    private final Logger mLogger;

    ///Log level we log to
    private final Level mLevel;

    ///The buffered output so far
    private final StringBuffer mOutput = new StringBuffer();

    ///Flag set to true once stream closed
    private boolean mClosed;

    //Prefix to add to log message
    private String mPrefix;
    /**
     * Construct LoggingOutputStream to write to a particular logger at a particular level.
     *
     * @param logger the logger to write to
     * @param level the level at which to log
     */
    public StreamToLogger( final Logger logger, final Level level, String prefix) {
        mLogger = logger;
        mLevel = level;
        mPrefix = prefix;
    }

    /**
     * Shutdown stream.
     * @exception java.io.IOException if an error occurs while closing the stream
     */
    public void close() throws IOException {
        flush();
        super.close();
        mClosed = true;
    }

    /**
     * Write a single byte of data to output stream.
     *
     * @param data the byte of data
     * @exception java.io.IOException if an error occurs
     */
    public void write( final int data ) throws IOException {
        checkValid();

        mOutput.append( (char)data );

        if( '\n' == data ){
            flush();
        }

    }

    public void write(byte[] b, int off, int len) throws IOException {
        super.write(b, off, len);
    }

    public void write(byte[] b) throws IOException {
        super.write(b);
    }


    /**
     * Flush data to underlying logger.
     *
     * @exception java.io.IOException if an error occurs
     */
    public synchronized void flush() throws IOException {
        checkValid();
        if (mOutput.charAt(mOutput.length()-1) == '\n')
            mOutput.deleteCharAt(mOutput.length()-1);
        mLogger.log( mLevel, mPrefix + mOutput.toString() );
        mOutput.setLength( 0 );
    }

    /**
     * Make sure stream is valid.
     *
     * @exception java.io.IOException if an error occurs
     */
    private void checkValid() throws IOException {
        if( true == mClosed ) {
            throw new EOFException( "LoggingOutputStream closed" );
        }
    }
}
