// Copyright 2026 The Cobalt Authors. All Rights Reserved.
// Licensed under the Apache License, Version 2.0.
package dev.cobalt.coat;

import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.*;
import org.junit.Test;

public class UpdateDownloaderTest {
  private static final String URL = "https://example.com/app.apk";
  static class Connection extends HttpURLConnection {
    int status; long length; String location; byte[] content;
    Connection(int status, byte[] content, long length) throws Exception {
      super(new URL(URL)); this.status=status; this.content=content; this.length=length;
    }
    public int getResponseCode() { return status; }
    public long getContentLengthLong() { return length; }
    public String getHeaderField(String key) { return location; }
    public InputStream getInputStream() { return new ByteArrayInputStream(content); }
    public void disconnect() {} public boolean usingProxy() { return false; }
    public void connect() {}
  }
  static class Listener implements UpdateDownloader.Listener {
    boolean cancelled; long bytes; long total; int retries;
    public boolean isCancelled() { return cancelled; }
    public void onProgress(long bytes, long total, int attempt) {
      this.bytes=bytes; this.total=total;
    }
    public void onRetry(int next) { retries++; }
  }
  private File target() throws Exception {
    File dir=Files.createTempDirectory("updater-test").toFile();
    dir.deleteOnExit(); File file=new File(dir,"update.apk"); file.deleteOnExit(); return file;
  }
  private void failure(UpdateDownloader downloader, File file, Listener listener,
      UpdateDownloader.Failure expected) throws Exception {
    try { downloader.download(URL,file,listener); fail("Expected failure"); }
    catch (UpdateDownloader.DownloadException error) { assertEquals(expected,error.failure); }
    assertFalse(file.exists()); assertFalse(new File(file+".part").exists());
  }
  @Test public void reportsProgressForKnownAndUnknownSizes() throws Exception {
    for(long size : new long[]{3,-1}) {
      File file=target(); Listener listener=new Listener();
      new UpdateDownloader(url -> { try { return new Connection(200,new byte[]{1,2,3},size); }
        catch(Exception e) { throw new IOException(e); } }, millis -> {}).download(URL,file,listener);
      assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(file.toPath()));
      assertEquals(3,listener.bytes); assertEquals(size,listener.total);
      assertFalse(new File(file+".part").exists());
    }
  }
  @Test public void retriesTransientErrorsThenSucceeds() throws Exception {
    int[] calls={0}; Listener listener=new Listener(); List<Long> delays=new ArrayList<>();
    UpdateDownloader downloader=new UpdateDownloader(url -> {
      try { return ++calls[0]<3 ? new Connection(503,new byte[0],0)
          : new Connection(200,new byte[]{4},1); }
      catch(Exception e) { throw new IOException(e); }
    }, delays::add);
    downloader.download(URL,target(),listener);
    assertEquals(3,calls[0]); assertEquals(2,listener.retries);
    assertEquals(Arrays.asList(1000L,2000L),delays);
  }
  @Test public void networkRetriesAreBoundedAndCleanPartialFiles() throws Exception {
    int[] calls={0}; Listener listener=new Listener(); File file=target();
    UpdateDownloader downloader=new UpdateDownloader(url -> {
      calls[0]++; throw new IOException("offline"); }, millis -> {});
    try { downloader.download(URL,file,listener); fail(); } catch(IOException expected) {}
    assertEquals(3,calls[0]); assertEquals(2,listener.retries);
    assertFalse(file.exists()); assertFalse(new File(file+".part").exists());
  }
  @Test public void permanentHttpErrorsAreNotRetried() throws Exception {
    int[] calls={0}; Listener listener=new Listener();
    failure(new UpdateDownloader(url -> { calls[0]++;
      try { return new Connection(404,new byte[0],0); }
      catch(Exception e) { throw new IOException(e); }
    }, millis -> {}),target(),listener,UpdateDownloader.Failure.HTTP);
    assertEquals(1,calls[0]); assertEquals(0,listener.retries);
  }
  @Test public void truncatedAndEmptyTransfersNeverBecomeApks() throws Exception {
    for(byte[] data : new byte[][]{new byte[0],new byte[]{1}}) {
      Listener listener=new Listener();
      failure(new UpdateDownloader(url -> { try { return new Connection(200,data,2); }
        catch(Exception e) { throw new IOException(e); } }, millis -> {}),
          target(),listener,UpdateDownloader.Failure.NETWORK);
      assertEquals(2,listener.retries);
    }
  }
  @Test public void refusesOversizedResponsesBeforeReading() throws Exception {
    failure(new UpdateDownloader(url -> { try {
      return new Connection(200,new byte[0],UpdateDownloader.MAX_BYTES+1);
    } catch(Exception e) { throw new IOException(e); } }, millis -> {}),
        target(),new Listener(),UpdateDownloader.Failure.TOO_LARGE);
  }
  @Test public void cancellationCleansPartialDownload() throws Exception {
    Listener listener=new Listener() {
      public void onProgress(long bytes,long total,int attempt) { cancelled=true; }
    };
    failure(new UpdateDownloader(url -> { try { return new Connection(200,new byte[]{1},1); }
      catch(Exception e) { throw new IOException(e); } }, millis -> {}),
        target(),listener,UpdateDownloader.Failure.CANCELLED);
    assertEquals(0,listener.retries);
  }
  @Test public void rejectsInsecureRedirects() throws Exception {
    failure(new UpdateDownloader(url -> { try {
      Connection response=new Connection(302,new byte[0],0);
      response.location="http://example.com/app.apk"; return response;
    } catch(Exception e) { throw new IOException(e); } }, millis -> {}),
        target(),new Listener(),UpdateDownloader.Failure.INVALID_URL);
  }
  @Test public void acceptsHttpsRedirects() throws Exception {
    int[] calls={0};
    new UpdateDownloader(url -> { try {
      Connection response=new Connection(++calls[0]==1 ? 302 : 200,new byte[]{1},1);
      response.location="https://cdn.example.com/file.apk"; return response;
    } catch(Exception e) { throw new IOException(e); } }, millis -> {})
        .download(URL,target(),new Listener());
    assertEquals(2,calls[0]);
  }
  @Test public void validatesUrls() throws Exception {
    for(String value : new String[]{null,"http://host/file","file:///tmp/a","https://u:p@host/a","https://host:8080/a"}) {
      try { UpdateDownloader.validateUrl(value); fail(); }
      catch(UpdateDownloader.DownloadException error) {
        assertEquals(UpdateDownloader.Failure.INVALID_URL,error.failure);
      }
    }
    assertEquals("example.com",UpdateDownloader.validateUrl(URL).getHost());
  }
}
