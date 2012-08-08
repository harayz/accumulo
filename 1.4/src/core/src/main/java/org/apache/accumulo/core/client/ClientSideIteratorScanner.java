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
package org.apache.accumulo.core.client;

import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.impl.ScannerOptions;
import org.apache.accumulo.core.client.mock.IteratorAdapter;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.data.ArrayByteSequence;
import org.apache.accumulo.core.data.ByteSequence;
import org.apache.accumulo.core.data.Column;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.data.thrift.IterInfo;
import org.apache.accumulo.core.iterators.IteratorEnvironment;
import org.apache.accumulo.core.iterators.IteratorUtil;
import org.apache.accumulo.core.iterators.IteratorUtil.IteratorScope;
import org.apache.accumulo.core.iterators.SortedKeyValueIterator;
import org.apache.hadoop.io.Text;

/**
 * A scanner that instantiates iterators on the client side instead of on the tablet server. This can be useful for testing iterators or in cases where you
 * don't want iterators affecting the performance of tablet servers.<br>
 * <br>
 * Suggested usage:<br>
 * <code>Scanner scanner = new ClientSideIteratorScanner(connector.createScanner(tableName, authorizations));</code><br>
 * <br>
 * Iterators added to this scanner will be run in the client JVM. Separate scan iterators can be run on the server side and client side by adding iterators to
 * the source scanner (which will execute server side) and to the client side scanner (which will execute client side).
 */
public class ClientSideIteratorScanner extends ScannerOptions implements Scanner {
  private int size;
  private int timeOut;
  
  private Range range;
  private boolean isolated = false;
  
  /**
   * A class that wraps a Scanner in a SortedKeyValueIterator so that other accumulo iterators can use it as a source.
   */
  public class ScannerTranslator implements SortedKeyValueIterator<Key,Value> {
    protected Scanner scanner;
    Iterator<Entry<Key,Value>> iter;
    Entry<Key,Value> top = null;
    
    /**
     * Constructs an accumulo iterator from a scanner.
     * 
     * @param scanner
     *          the scanner to iterate over
     */
    public ScannerTranslator(Scanner scanner) {
      this.scanner = scanner;
    }
    
    @Override
    public void init(SortedKeyValueIterator<Key,Value> source, Map<String,String> options, IteratorEnvironment env) throws IOException {
      throw new UnsupportedOperationException();
    }
    
    @Override
    public boolean hasTop() {
      return top != null;
    }
    
    @Override
    public void next() throws IOException {
      if (iter.hasNext())
        top = iter.next();
      else
        top = null;
    }
    
    @Override
    public void seek(Range range, Collection<ByteSequence> columnFamilies, boolean inclusive) throws IOException {
      if (!inclusive && columnFamilies.size() > 0)
        throw new IllegalArgumentException();
      scanner.setRange(range);
      scanner.clearColumns();
      for (ByteSequence colf : columnFamilies) {
        scanner.fetchColumnFamily(new Text(colf.toArray()));
      }
      iter = scanner.iterator();
      next();
    }
    
    @Override
    public Key getTopKey() {
      return top.getKey();
    }
    
    @Override
    public Value getTopValue() {
      return top.getValue();
    }
    
    @Override
    public SortedKeyValueIterator<Key,Value> deepCopy(IteratorEnvironment env) {
      return new ScannerTranslator(scanner);
    }
  }
  
  private ScannerTranslator smi;
  
  /**
   * Constructs a scanner that can execute client-side iterators.
   * 
   * @param scanner
   *          the source scanner
   */
  public ClientSideIteratorScanner(Scanner scanner) {
    smi = new ScannerTranslator(scanner);
    this.range = new Range((Key) null, (Key) null);
    this.size = Constants.SCAN_BATCH_SIZE;
    this.timeOut = Integer.MAX_VALUE;
  }
  
  /**
   * Sets the source Scanner.
   * 
   * @param scanner
   */
  public void setSource(Scanner scanner) {
    smi = new ScannerTranslator(scanner);
  }
  
  @Override
  public Iterator<Entry<Key,Value>> iterator() {
    smi.scanner.setBatchSize(size);
    smi.scanner.setTimeOut(timeOut);
    if (isolated)
      smi.scanner.enableIsolation();
    else
      smi.scanner.disableIsolation();
    
    TreeMap<Integer,IterInfo> tm = new TreeMap<Integer,IterInfo>();
    
    for (IterInfo iterInfo : serverSideIteratorList) {
      tm.put(iterInfo.getPriority(), iterInfo);
    }
    
    SortedKeyValueIterator<Key,Value> skvi;
    try {
      skvi = IteratorUtil.loadIterators(smi, tm.values(), serverSideIteratorOptions, new IteratorEnvironment() {
        @Override
        public SortedKeyValueIterator<Key,Value> reserveMapFileReader(String mapFileName) throws IOException {
          return null;
        }
        
        @Override
        public AccumuloConfiguration getConfig() {
          return null;
        }
        
        @Override
        public IteratorScope getIteratorScope() {
          return null;
        }
        
        @Override
        public boolean isFullMajorCompaction() {
          return false;
        }
        
        @Override
        public void registerSideChannel(SortedKeyValueIterator<Key,Value> iter) {}
      }, false);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    
    Set<ByteSequence> colfs = new TreeSet<ByteSequence>();
    for (Column c : this.getFetchedColumns()) {
      colfs.add(new ArrayByteSequence(c.getColumnFamily()));
    }
    
    try {
      skvi.seek(range, colfs, true);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    
    return new IteratorAdapter(skvi);
  }
  
  @Override
  public void setTimeOut(int timeOut) {
    this.timeOut = timeOut;
  }
  
  @Override
  public int getTimeOut() {
    return timeOut;
  }
  
  @Override
  public void setRange(Range range) {
    this.range = range;
  }
  
  @Override
  public Range getRange() {
    return range;
  }
  
  @Override
  public void setBatchSize(int size) {
    this.size = size;
  }
  
  @Override
  public int getBatchSize() {
    return size;
  }
  
  @Override
  public void enableIsolation() {
    this.isolated = true;
  }
  
  @Override
  public void disableIsolation() {
    this.isolated = false;
  }
}
