/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.gc;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.gc.thrift.GCStatus;
import org.apache.accumulo.core.gc.thrift.GcCycleStats;
import org.apache.accumulo.core.tabletserver.log.LogEntry;
import org.apache.accumulo.core.tabletserver.thrift.TabletClientService;
import org.apache.accumulo.core.tabletserver.thrift.TabletClientService.Client;
import org.apache.accumulo.core.util.AddressUtil;
import org.apache.accumulo.core.util.ThriftUtil;
import org.apache.accumulo.core.zookeeper.ZooUtil;
import org.apache.accumulo.server.ServerConstants;
import org.apache.accumulo.server.conf.ServerConfiguration;
import org.apache.accumulo.server.fs.VolumeManager;
import org.apache.accumulo.server.fs.VolumeManager.FileType;
import org.apache.accumulo.server.security.SystemCredentials;
import org.apache.accumulo.server.util.MetadataTableUtil;
import org.apache.accumulo.server.zookeeper.ZooReaderWriter;
import org.apache.accumulo.trace.instrument.Span;
import org.apache.accumulo.trace.instrument.Trace;
import org.apache.accumulo.trace.instrument.Tracer;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.log4j.Logger;
import org.apache.thrift.TException;
import org.apache.zookeeper.KeeperException;

import com.google.common.net.HostAndPort;

public class GarbageCollectWriteAheadLogs {
  private static final Logger log = Logger.getLogger(GarbageCollectWriteAheadLogs.class);
  
  private final Instance instance;
  private final VolumeManager fs;
  
  private boolean useTrash;
  
  GarbageCollectWriteAheadLogs(Instance instance, VolumeManager fs, boolean useTrash) throws IOException {
    this.instance = instance;
    this.fs = fs;
  }
  
  public void collect(GCStatus status) {
    
    Span span = Trace.start("scanServers");
    try {
      
      Map<String, Path> sortedWALogs = getSortedWALogs();
      
      status.currentLog.started = System.currentTimeMillis();
      
      Map<Path,String> fileToServerMap = new HashMap<Path,String>();
      Map<String,Path> nameToFileMap = new HashMap<String, Path>();
      int count = scanServers(fileToServerMap, nameToFileMap);
      long fileScanStop = System.currentTimeMillis();
      log.info(String.format("Fetched %d files from %d servers in %.2f seconds", fileToServerMap.size(), count,
          (fileScanStop - status.currentLog.started) / 1000.));
      status.currentLog.candidates = fileToServerMap.size();
      span.stop();
      
      span = Trace.start("removeMetadataEntries");
      try {
        count = removeMetadataEntries(nameToFileMap, sortedWALogs, status);
      } catch (Exception ex) {
        log.error("Unable to scan metadata table", ex);
        return;
      } finally {
        span.stop();
      }
      
      long logEntryScanStop = System.currentTimeMillis();
      log.info(String.format("%d log entries scanned in %.2f seconds", count, (logEntryScanStop - fileScanStop) / 1000.));
      
      span = Trace.start("removeFiles");
      Map<String,ArrayList<Path>> serverToFileMap = mapServersToFiles(fileToServerMap, nameToFileMap);
      
      count = removeFiles(nameToFileMap, serverToFileMap, sortedWALogs, status);
      
      long removeStop = System.currentTimeMillis();
      log.info(String.format("%d total logs removed from %d servers in %.2f seconds", count, serverToFileMap.size(), (removeStop - logEntryScanStop) / 1000.));
      status.currentLog.finished = removeStop;
      status.lastLog = status.currentLog;
      status.currentLog = new GcCycleStats();
      span.stop();
      
    } catch (Exception e) {
      log.error("exception occured while garbage collecting write ahead logs", e);
    } finally {
      span.stop();
    }
  }
  
  boolean holdsLock(HostAndPort addr) {
    try {
      String zpath = ZooUtil.getRoot(instance) + Constants.ZTSERVERS + "/" + addr.toString();
      List<String> children = ZooReaderWriter.getInstance().getChildren(zpath);
      return !(children == null || children.isEmpty());
    } catch (KeeperException.NoNodeException ex) {
      return false;
    } catch (Exception ex) {
      log.debug(ex, ex);
      return true;
    }
  }
  
  private int removeFiles(Map<String,Path> nameToFileMap, Map<String,ArrayList<Path>> serverToFileMap, Map<String, Path> sortedWALogs, final GCStatus status) {
    AccumuloConfiguration conf = ServerConfiguration.getSystemConfiguration(instance);
    for (Entry<String,ArrayList<Path>> entry : serverToFileMap.entrySet()) {
      if (entry.getKey().isEmpty()) {
        // old-style log entry, just remove it
        for (Path path : entry.getValue()) {
          log.debug("Removing old-style WAL " + path);
          try {
            if (!useTrash || !fs.moveToTrash(path))
              fs.deleteRecursively(path);
            status.currentLog.deleted++;
          } catch (FileNotFoundException ex) {
            // ignored
          } catch (IOException ex) {
            log.error("Unable to delete wal " + path + ": " + ex);
          }
        }
      } else {
        HostAndPort address = AddressUtil.parseAddress(entry.getKey());
        if (!holdsLock(address)) {
          for (Path path : entry.getValue()) {
            log.debug("Removing WAL for offline server " + path);
            try {
              if (!useTrash || !fs.moveToTrash(path))
                fs.deleteRecursively(path);
              status.currentLog.deleted++;
            } catch (FileNotFoundException ex) {
              // ignored
            } catch (IOException ex) {
              log.error("Unable to delete wal " + path + ": " + ex);
            }
          }
          continue;
        } else {
          Client tserver = null;
          try {
            tserver = ThriftUtil.getClient(new TabletClientService.Client.Factory(), address, conf);
            tserver.removeLogs(Tracer.traceInfo(), SystemCredentials.get().toThrift(instance), paths2strings(entry.getValue()));
            log.debug("deleted " + entry.getValue() + " from " + entry.getKey());
            status.currentLog.deleted += entry.getValue().size();
          } catch (TException e) {
            log.warn("Error talking to " + address + ": " + e);
          } finally {
            if (tserver != null)
              ThriftUtil.returnClient(tserver);
          }
        }
      }
    }
    
    for (Path swalog : sortedWALogs.values()) {
      log.debug("Removing sorted WAL " + swalog);
      try {
        if (!useTrash || !fs.moveToTrash(swalog)) {
          fs.deleteRecursively(swalog);
        }
      } catch (FileNotFoundException ex) {
        // ignored
      } catch (IOException ioe) {
        try {
          if (fs.exists(swalog)) {
            log.error("Unable to delete sorted walog " + swalog + ": " + ioe);
          }
        } catch (IOException ex) {
          log.error("Unable to check for the existence of " + swalog, ex);
        }
      }
    }
    
    return 0;
  }
  
  private List<String> paths2strings(ArrayList<Path> paths) {
    List<String> result = new ArrayList<String>(paths.size());
    for (Path path : paths)
      result.add(path.toString());
    return result;
  }
  
  private static Map<String,ArrayList<Path>> mapServersToFiles(Map<Path,String> fileToServerMap, Map<String,Path> nameToFileMap) {
    Map<String,ArrayList<Path>> result = new HashMap<String,ArrayList<Path>>();
    for (Entry<Path,String> fileServer : fileToServerMap.entrySet()) {
      if (!nameToFileMap.containsKey(fileServer.getKey().getName()))
        continue;
      ArrayList<Path> files = result.get(fileServer.getValue());
      if (files == null) {
        files = new ArrayList<Path>();
        result.put(fileServer.getValue(), files);
      }
      files.add(fileServer.getKey());
    }
    return result;
  }
  
  private int removeMetadataEntries(Map<String,Path>  nameToFileMap, Map<String, Path> sortedWALogs, GCStatus status) throws IOException, KeeperException,
      InterruptedException {
    int count = 0;
    Iterator<LogEntry> iterator = MetadataTableUtil.getLogEntries(SystemCredentials.get());
    while (iterator.hasNext()) {
      for (String entry : iterator.next().logSet) {
        String parts[] = entry.split("/", 2);
        String filename = parts[1];
        Path path;
        if (filename.contains(":"))
          path = new Path(filename);
        else
          path = fs.getFullPath(FileType.WAL, filename);
        
        Path pathFromNN = nameToFileMap.remove(path.getName());
        if (pathFromNN != null) {
          status.currentLog.inUse++;
          sortedWALogs.remove(path.getName());
        }
        count++;
      }
    }
    return count;
  }

  //TODO Remove deprecation warning suppression when Hadoop1 support is dropped
  @SuppressWarnings("deprecation")
  private int scanServers(Map<Path,String> fileToServerMap, Map<String,Path> nameToFileMap) throws Exception {
    Set<String> servers = new HashSet<String>();
    for (String walDir : ServerConstants.getWalDirs()) {
      Path walRoot = new Path(walDir);
      FileStatus[] listing = fs.listStatus(walRoot);
      if (listing == null)
        continue;
      for (FileStatus status : listing) {
        String server = status.getPath().getName();
        servers.add(server);
        if (status.isDir()) {
          for (FileStatus file : fs.listStatus(new Path(walRoot, server))) {
            if (isUUID(file.getPath().getName())) {
              fileToServerMap.put(file.getPath(), server);
              nameToFileMap.put(file.getPath().getName(), file.getPath());
            } else {
              log.info("Ignoring file " + file.getPath() + " because it doesn't look like a uuid");
            }
          }
        } else if (isUUID(server)) {
          // old-style WAL are not under a directory
          fileToServerMap.put(status.getPath(), "");
        } else {
          log.info("Ignoring file " + status.getPath() + " because it doesn't look like a uuid");
        }
      }
    }
    return servers.size();
  }
  
  private Map<String, Path> getSortedWALogs() throws IOException {
    Map<String, Path> result = new HashMap<String, Path>();
    
    for (String dir : ServerConstants.getRecoveryDirs()) {
      Path recoveryDir = new Path(dir);
      
      if (fs.exists(recoveryDir)) {
        for (FileStatus status : fs.listStatus(recoveryDir)) {
          String name = status.getPath().getName();
          if (isUUID(name)) {
            result.put(name, status.getPath());
          } else {
            log.debug("Ignoring file " + status.getPath() + " because it doesn't look like a uuid");
          }
        }
      }
    }
    return result;
  }
  
  static private boolean isUUID(String name) {
    try {
      UUID.fromString(name);
      return true;
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }
  
}
