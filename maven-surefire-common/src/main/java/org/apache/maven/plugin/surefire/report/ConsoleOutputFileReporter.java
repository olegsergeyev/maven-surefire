package org.apache.maven.plugin.surefire.report;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.maven.surefire.report.ReportEntry;

import static org.apache.maven.plugin.surefire.report.FileReporter.getReportFile;

/**
 * Surefire output consumer proxy that writes test output to a {@link java.io.File} for each test suite.
 * <br>
 * This class is not threadsafe, but can be serially handed off from thread to thread.
 *
 * @author Kristian Rosenvold
 * @author Carlos Sanchez
 */
public class ConsoleOutputFileReporter
    implements TestcycleConsoleOutputReceiver
{
    private final File reportsDirectory;

    private final String reportNameSuffix;

    private final AtomicStampedReference<FilterOutputStream> fileOutputStream =
            new AtomicStampedReference<FilterOutputStream>( null, 0 );

    private final ReentrantLock lock = new ReentrantLock();

    private String reportEntryName;

    public ConsoleOutputFileReporter( File reportsDirectory, String reportNameSuffix )
    {
        this.reportsDirectory = reportsDirectory;
        this.reportNameSuffix = reportNameSuffix;
    }

    @Override
    public void testSetStarting( ReportEntry reportEntry )
    {
        close( true );
        reportEntryName = reportEntry.getName();
    }

    @Override
    public void testSetCompleted( ReportEntry report )
    {
    }

    @Override
    public void close()
    {
        // The close() method is called in main Thread T2.
        close( false );
    }

    @Override
    public void writeTestOutput( byte[] buf, int off, int len, boolean stdout )
    {
        lock.lock();
        try
        {
            // This method is called in single thread T1 per fork JVM (see ThreadedStreamConsumer).
            // The close() method is called in main Thread T2.
            int[] stamp = {0};
            FilterOutputStream os = fileOutputStream.get( stamp );
            if ( stamp[0] != 2 )
            {
                if ( os == null )
                {
                    if ( !reportsDirectory.exists() )
                    {
                        //noinspection ResultOfMethodCallIgnored
                        reportsDirectory.mkdirs();
                    }
                    File file = getReportFile( reportsDirectory, reportEntryName, reportNameSuffix, "-output.txt" );
                    os = new BufferedOutputStream( new FileOutputStream( file ), 16 * 1024 );
                    fileOutputStream.set( os, 0 );
                }
                os.write( buf, off, len );
            }
        }
        catch ( IOException e )
        {
            throw new RuntimeException( e );
        }
        finally
        {
            lock.unlock();
        }
    }

    @SuppressWarnings( "checkstyle:emptyblock" )
    private void close( boolean closeReattempt )
    {
        lock.lock();
        try
        {
            int[] stamp = {0};
            FilterOutputStream os = fileOutputStream.get( stamp );
            if ( stamp[0] != 2 )
            {
                fileOutputStream.set( null, closeReattempt ? 1 : 2 );
                if ( os != null && stamp[0] == 0 )
                {
                    os.close();
                }
            }
        }
        catch ( IOException ignored )
        {
        }
        finally
        {
            lock.unlock();
        }
    }
}
