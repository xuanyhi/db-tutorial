package io.github.dunwu.javadb.hbase;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.core.util.StrUtil;
import io.github.dunwu.javadb.hbase.entity.BaseHbaseEntity;
import io.github.dunwu.javadb.hbase.entity.common.ColumnDo;
import io.github.dunwu.javadb.hbase.entity.common.FamilyDo;
import io.github.dunwu.javadb.hbase.entity.common.PageData;
import io.github.dunwu.javadb.hbase.entity.common.RowDo;
import io.github.dunwu.javadb.hbase.entity.common.ScrollData;
import io.github.dunwu.javadb.hbase.entity.scan.MultiFamilyScan;
import io.github.dunwu.javadb.hbase.entity.scan.SingleFamilyScan;
import io.github.dunwu.javadb.hbase.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Durability;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Row;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.security.User;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.security.UserGroupInformation;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * HBase 客户端封装工具类
 *
 * @author <a href="mailto:forbreak@163.com">Zhang Peng</a>
 * @date 2023-03-27
 */
@Slf4j
public class HbaseTemplate implements Closeable {

    private final Connection connection;

    private final Configuration configuration;

    protected HbaseTemplate(Configuration configuration) throws IOException {
        this.configuration = configuration;
        // 无需鉴权连接
        // this.connection = ConnectionFactory.createConnection(configuration);
        // 鉴权连接
        this.connection = ConnectionFactory.createConnection(configuration, null,
            new User.SecureHadoopUser(UserGroupInformation.createRemoteUser("test")));
    }

    protected HbaseTemplate(Connection connection) {
        this.configuration = connection.getConfiguration();
        this.connection = connection;
    }

    public static synchronized HbaseTemplate newInstance(Configuration configuration) throws IOException {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration can not be null!");
        }
        return new HbaseTemplate(configuration);
    }

    public synchronized static HbaseTemplate newInstance(Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("connection can not be null!");
        }
        return new HbaseTemplate(connection);
    }

    /**
     * 关闭内部持有的 HBase Connection 实例
     */
    @Override
    public synchronized void close() {
        if (null == connection || connection.isClosed()) {
            return;
        }
        IoUtil.close(connection);
    }

    /**
     * 获取 HBase 连接实例
     *
     * @return /
     */
    public Connection getConnection() {
        if (null == connection) {
            throw new RuntimeException("HBase connection init failed...");
        }
        return connection;
    }

    /**
     * 获取 HBase 配置
     *
     * @return /
     */
    public Configuration getConfiguration() {
        return configuration;
    }

    /**
     * 获取 {@link org.apache.hadoop.hbase.client.Table} 实例
     *
     * @param tableName 表名
     * @return /
     */
    public Table getTable(String tableName) throws IOException {
        return getTable(TableName.valueOf(tableName));
    }

    /**
     * 获取 {@link org.apache.hadoop.hbase.client.Table} 实例
     *
     * @param tableName 表名
     * @return /
     */

    public synchronized Table getTable(TableName tableName) throws IOException {
        return connection.getTable(tableName);
    }

    // =====================================================================================
    // put 操作封装
    // =====================================================================================

    public void put(String tableName, Put put) throws IOException {
        if (StrUtil.isBlank(tableName) || put == null) {
            return;
        }
        Table table = getTable(tableName);
        try {
            table.put(put);
        } finally {
            recycle(table);
        }
    }

    public void put(String tableName, String row, String family, String column, String value)
        throws IOException {
        Put put = newPut(row, null, family, column, value);
        put(tableName, put);
    }

    public void put(String tableName, String row, Long timestamp, String family, String column, String value)
        throws IOException {
        Put put = newPut(row, timestamp, family, column, value);
        put(tableName, put);
    }

    public void put(String tableName, String row, String family, Object obj) throws IOException {
        put(tableName, row, null, family, obj);
    }

    public void put(String tableName, String row, Long timestamp, String family, Object obj) throws IOException {
        Put put = newPut(row, timestamp, family, obj);
        put(tableName, put);
    }

    public void put(String tableName, String row, String family, Map<String, Object> columnMap)
        throws IOException {
        Put put = newPut(row, null, family, columnMap);
        put(tableName, put);
    }

    public void put(String tableName, String row, Long timestamp, String family, Map<String, Object> columnMap)
        throws IOException {
        Put put = newPut(row, timestamp, family, columnMap);
        put(tableName, put);
    }

    public void put(String tableName, String row, Long timestamp, Map<String, Map<String, Object>> familyMap)
        throws IOException {
        Put put = newPut(row, timestamp, familyMap);
        put(tableName, put);
    }

    public <T extends BaseHbaseEntity> void put(String tableName, String family, T entity) throws IOException {
        put(tableName, entity.getRowKey(), family, entity);
    }

    public void batchPut(String tableName, Collection<Put> list) throws IOException, InterruptedException {
        batch(tableName, list);
    }

    public <T extends BaseHbaseEntity> void batchPut(String tableName, String family, Collection<T> list)
        throws IOException, InterruptedException {
        if (StrUtil.isBlank(tableName) || StrUtil.isBlank(family) || CollectionUtil.isEmpty(list)) {
            return;
        }
        List<Put> puts = newPutList(family, list);
        batchPut(tableName, puts);
    }

    public static Put newPut(String row, Long timestamp, String family, String column, String value) {
        if (StrUtil.isBlank(row) || StrUtil.isBlank(family) || StrUtil.isBlank(column) || StrUtil.isBlank(value)) {
            return null;
        }
        Map<String, Object> columnMap = new LinkedHashMap<>(1);
        columnMap.put(column, value);
        return newPut(row, timestamp, family, columnMap);
    }

    public static Put newPut(String row, Long timestamp, String family, Map<String, Object> columnMap) {
        if (StrUtil.isBlank(row) || StrUtil.isBlank(family) || MapUtil.isEmpty(columnMap)) {
            return null;
        }
        Map<String, Map<String, Object>> familyMap = new LinkedHashMap<>(1);
        familyMap.put(family, columnMap);
        return newPut(row, timestamp, familyMap);
    }

    public static Put newPut(String row, Long timestamp, String family, Object obj) {
        if (obj == null) {
            return null;
        }
        Map<String, Object> columnMap = JsonUtil.toMap(obj);
        return newPut(row, timestamp, family, columnMap);
    }

    public static Put newPut(String row, Long timestamp, Map<String, Map<String, Object>> familyMap) {

        if (StrUtil.isBlank(row) || MapUtil.isEmpty(familyMap)) {
            return null;
        }

        if (timestamp == null) {
            timestamp = System.currentTimeMillis();
        }

        Put put = new Put(Bytes.toBytes(row));
        for (Map.Entry<String, Map<String, Object>> e : familyMap.entrySet()) {
            String family = e.getKey();
            Map<String, Object> columnMap = e.getValue();
            if (MapUtil.isNotEmpty(columnMap)) {
                for (Map.Entry<String, Object> entry : columnMap.entrySet()) {
                    String column = entry.getKey();
                    Object value = entry.getValue();
                    if (ObjectUtil.isEmpty(value)) {
                        continue;
                    }
                    if (value instanceof String) {
                        put.addColumn(Bytes.toBytes(family), Bytes.toBytes(column), timestamp,
                            Bytes.toBytes(value.toString()));
                    } else if (value instanceof Date) {
                        put.addColumn(Bytes.toBytes(family), Bytes.toBytes(column), timestamp,
                            Bytes.toBytes(DateUtil.format((Date) value, DatePattern.NORM_DATETIME_PATTERN)));
                    } else {
                        put.addColumn(Bytes.toBytes(family), Bytes.toBytes(column),
                            timestamp, Bytes.toBytes(JsonUtil.toString(value)));
                    }
                }
            }
        }
        return put;
    }

    private static <T extends BaseHbaseEntity> List<Put> newPutList(String family, Collection<T> list) {
        long timestamp = System.currentTimeMillis();
        List<Put> puts = new ArrayList<>();
        for (T entity : list) {
            Put put = newPut(entity.getRowKey(), timestamp, family, entity);
            puts.add(put);
        }
        return puts;
    }

    // =====================================================================================
    // delete 操作封装
    // =====================================================================================

    public void delete(String tableName, Delete delete) throws IOException {
        if (StrUtil.isBlank(tableName) || delete == null) {
            return;
        }
        Table table = getTable(tableName);
        try {
            table.delete(delete);
        } finally {
            recycle(table);
        }
    }

    public void delete(String tableName, String row) throws IOException {
        Delete delete = new Delete(Bytes.toBytes(row));
        delete(tableName, delete);
    }

    public void batchDelete(String tableName, String... rows) throws IOException, InterruptedException {
        if (ArrayUtil.isEmpty(rows)) {
            return;
        }
        List<Delete> deletes = Stream.of(rows)
                                     .map(row -> new Delete(Bytes.toBytes(row)))
                                     .distinct().collect(Collectors.toList());
        batchDelete(tableName, deletes);
    }

    public void batchDelete(String tableName, List<Delete> deletes) throws IOException, InterruptedException {
        batch(tableName, deletes);
    }

    // =====================================================================================
    // get 操作封装
    // =====================================================================================

    public Result get(String tableName, String row) throws IOException {
        if (StrUtil.isBlank(tableName) || StrUtil.isBlank(row)) {
            return null;
        }
        Get get = newGet(row);
        return get(tableName, get);
    }

    public Result get(String tableName, Get get) throws IOException {
        if (StrUtil.isBlank(tableName) || get == null) {
            return null;
        }
        Table table = getTable(tableName);
        try {
            return table.get(get);
        } finally {
            recycle(table);
        }
    }

    public Result[] batchGet(String tableName, String[] rows) throws IOException {
        if (StrUtil.isBlank(tableName) || ArrayUtil.isEmpty(rows)) {
            return null;
        }
        List<Get> gets = newGetList(rows);
        return batchGet(tableName, gets);
    }

    public Result[] batchGet(String tableName, List<Get> gets) throws IOException {
        if (StrUtil.isBlank(tableName) || CollectionUtil.isEmpty(gets)) {
            return null;
        }
        Table table = getTable(tableName);
        try {
            return table.get(gets);
        } finally {
            recycle(table);
        }
    }

    /**
     * 指定行、列族，以实体 {@link T} 形式返回数据
     *
     * @param tableName 表名
     * @param row       指定行
     * @param family    列族
     * @param clazz     返回实体类型
     * @param <T>       实体类型
     * @return /
     */
    public <T> T getEntity(String tableName, String row, String family, Class<T> clazz) throws IOException {

        if (StrUtil.isBlank(tableName) || StrUtil.isBlank(row) || StrUtil.isBlank(family) || clazz == null) {
            return null;
        }
        Map<String, Field> fieldMap = ReflectUtil.getFieldMap(clazz);
        String[] columns = fieldMap.keySet().toArray(new String[0]);
        Map<String, ColumnDo> columnMap = getColumnMap(tableName, row, family, columns);
        if (MapUtil.isEmpty(columnMap)) {
            return null;
        }
        return toEntity(ColumnDo.toKvMap(columnMap), clazz);
    }

    /**
     * 指定多行、列族，以实体 {@link T} 列表形式返回数据
     *
     * @param tableName 表名
     * @param rows      指定多行
     * @param family    列族
     * @param clazz     返回实体类型
     * @param <T>       实体类型
     * @return /
     */
    public <T> List<T> getEntityList(String tableName, String[] rows, String family, Class<T> clazz)
        throws IOException {
        Map<String, T> map = getEntityMap(tableName, rows, family, clazz);
        if (MapUtil.isEmpty(map)) {
            return new ArrayList<>(0);
        }
        return new ArrayList<>(map.values());
    }

    /**
     * 指定多行、列族，以实体 {@link T} 列表形式返回数据
     *
     * @param tableName 表名
     * @param rows      指定多行
     * @param family    列族
     * @param clazz     返回实体类型
     * @param <T>       实体类型
     * @return /
     */
    public <T> List<T> getEntityList(String tableName, Collection<String> rows, String family, Class<T> clazz)
        throws IOException {
        if (CollectionUtil.isEmpty(rows)) {
            return new ArrayList<>(0);
        }
        return getEntityList(tableName, rows.toArray(new String[0]), family, clazz);
    }

    public <T> Map<String, T> getEntityMap(String tableName, String[] rows, String family, Class<T> clazz)
        throws IOException {

        if (StrUtil.isBlank(tableName) || ArrayUtil.isEmpty(rows) || StrUtil.isBlank(family) || clazz == null) {
            return null;
        }

        Map<String, Field> fieldMap = ReflectUtil.getFieldMap(clazz);
        String[] columns = fieldMap.keySet().toArray(new String[0]);
        List<Get> gets = newGetList(rows, family, columns);

        Result[] results = batchGet(tableName, gets);
        if (ArrayUtil.isEmpty(results)) {
            return new LinkedHashMap<>(0);
        }

        Map<String, T> map = new LinkedHashMap<>(results.length);
        for (Result result : results) {
            Map<String, ColumnDo> columnMap =
                getColumnsFromResult(result, tableName, family, CollectionUtil.newArrayList(columns));
            if (MapUtil.isNotEmpty(columnMap)) {
                T entity = toEntity(ColumnDo.toKvMap(columnMap), clazz);
                map.put(Bytes.toString(result.getRow()), entity);
            }
        }
        return map;
    }

    public <T> Map<String, T> getEntityMap(String tableName, Collection<String> rows, String family, Class<T> clazz)
        throws IOException {
        if (CollectionUtil.isEmpty(rows)) {
            return new LinkedHashMap<>(0);
        }
        return getEntityMap(tableName, rows.toArray(new String[0]), family, clazz);
    }

    /**
     * 查询列信息
     *
     * @param tableName 表名
     * @param row       指定行
     * @param family    列族
     * @param column    列
     * @return /
     */
    public ColumnDo getColumn(String tableName, String row, String family, String column) throws IOException {

        if (StrUtil.isBlank(tableName) || StrUtil.isBlank(row) || StrUtil.isBlank(family) || StrUtil.isBlank(column)) {
            return null;
        }

        Result result = get(tableName, row);
        if (result == null) {
            return null;
        }

        return getColumnFromResult(result, tableName, family, column);
    }

    /**
     * 查询多列信息
     *
     * @param tableName 表名
     * @param row       指定行
     * @param family    列族
     * @param columns   指定列
     * @return /
     */
    public Map<String, ColumnDo> getColumnMap(String tableName, String row, String family, String... columns)
        throws IOException {

        if (StrUtil.isBlank(tableName) || StrUtil.isBlank(row) || StrUtil.isBlank(family)) {
            return null;
        }

        Get get = newGet(row, family, columns);
        Result result = get(tableName, get);
        if (result == null) {
            return null;
        }
        return getColumnsFromResult(result, tableName, family, Arrays.asList(columns));
    }

    /**
     * 查询列族信息
     *
     * @param tableName 表名
     * @param row       指定行
     * @param family    指定列族
     * @return /
     */
    public FamilyDo getFamily(String tableName, String row, String family) throws IOException {
        Map<String, ColumnDo> columnMap = getColumnMap(tableName, row, family);
        if (MapUtil.isEmpty(columnMap)) {
            return null;
        }
        return new FamilyDo(tableName, row, family, columnMap);
    }

    /**
     * 查询多列族信息
     *
     * @param tableName       表名
     * @param row             指定行
     * @param familyColumnMap <列族, 要查询的列>
     * @return /
     */
    public Map<String, FamilyDo> getFamilyMap(String tableName, String row,
        Map<String, Collection<String>> familyColumnMap) throws IOException {

        if (StrUtil.isBlank(tableName) || StrUtil.isBlank(row)) {
            return new LinkedHashMap<>(0);
        }

        if (MapUtil.isEmpty(familyColumnMap)) {
            RowDo rowDo = getRow(tableName, row);
            if (rowDo == null) {
                return new LinkedHashMap<>(0);
            }
            return rowDo.getFamilyMap();
        }

        Get get = newGet(row);
        for (Map.Entry<String, Collection<String>> entry : familyColumnMap.entrySet()) {
            String family = entry.getKey();
            Collection<String> columns = entry.getValue();
            if (CollectionUtil.isNotEmpty(columns)) {
                for (String column : columns) {
                    get.addColumn(Bytes.toBytes(family), Bytes.toBytes(column));
                }
            }
        }
        Result result = get(tableName, get);
        if (result == null) {
            return null;
        }

        return getFamiliesFromResult(result, tableName, familyColumnMap);
    }

    /**
     * 查询行信息
     *
     * @param tableName 表名
     * @param row       指定行
     * @return /
     */
    public RowDo getRow(String tableName, String row) throws IOException {
        if (StrUtil.isBlank(tableName) || StrUtil.isBlank(row)) {
            return null;
        }
        Result result = get(tableName, row);
        if (result == null) {
            return null;
        }
        return getRowFromResult(result, tableName);
    }

    /**
     * 查询多行信息
     *
     * @param tableName 表名
     * @param rows      指定多行
     * @return /
     */
    public Map<String, RowDo> getRowMap(String tableName, String... rows) throws IOException {
        if (StrUtil.isBlank(tableName) || ArrayUtil.isEmpty(rows)) {
            return null;
        }
        Result[] results = batchGet(tableName, rows);
        if (ArrayUtil.isEmpty(results)) {
            return new LinkedHashMap<>(0);
        }
        Map<String, RowDo> map = new LinkedHashMap<>(results.length);
        for (Result result : results) {
            String row = Bytes.toString(result.getRow());
            RowDo rowDo = getRowFromResult(result, tableName);
            map.put(row, rowDo);
        }
        return map;
    }

    private static Get newGet(String row) {
        return new Get(Bytes.toBytes(row));
    }

    private static Get newGet(String row, String family, String... columns) {
        Get get = newGet(row);
        get.addFamily(Bytes.toBytes(family));
        if (ArrayUtil.isNotEmpty(columns)) {
            for (String column : columns) {
                get.addColumn(Bytes.toBytes(family), Bytes.toBytes(column));
            }
        }
        return get;
    }

    private static List<Get> newGetList(String[] rows) {
        if (ArrayUtil.isEmpty(rows)) {
            return new ArrayList<>();
        }
        return Stream.of(rows).map(HbaseTemplate::newGet).collect(Collectors.toList());
    }

    private static List<Get> newGetList(String[] rows, String family, String[] columns) {
        if (ArrayUtil.isEmpty(rows)) {
            return new ArrayList<>();
        }
        return Stream.of(rows).map(row -> newGet(row, family, columns)).collect(Collectors.toList());
    }

    // =====================================================================================
    // scan 操作封装
    // =====================================================================================

    /**
     * 返回匹配 {@link org.apache.hadoop.hbase.client.Scan} 的所有列族的数据
     *
     * @param tableName 表名
     * @param scan      {@link org.apache.hadoop.hbase.client.Scan} 实体
     * @return /
     */
    public Result[] scan(String tableName, Scan scan) throws IOException {
        Table table = getTable(tableName);
        ResultScanner scanner = null;
        try {
            scanner = table.getScanner(scan);
            return ArrayUtil.toArray(scanner, Result.class);
        } finally {
            IoUtil.close(scanner);
            recycle(table);
        }
    }

    public PageData<RowDo> page(SingleFamilyScan scan) throws IOException {
        if (scan == null) {
            return null;
        }
        return getPageData(scan.getTableName(), scan.getPage(), scan.getSize(), scan.toScan(),
            scan.getFamilyColumnMap());
    }

    public PageData<RowDo> page(MultiFamilyScan scan) throws IOException {
        if (scan == null) {
            return null;
        }
        return getPageData(scan.getTableName(), scan.getPage(), scan.getSize(), scan.toScan(),
            scan.getFamilyColumnMap());
    }

    public ScrollData<RowDo> scroll(SingleFamilyScan scan) throws IOException {
        if (scan == null) {
            return null;
        }
        return getScrollData(scan.getTableName(), scan.getSize(), scan.toScan(), scan.getFamilyColumnMap());
    }

    public ScrollData<RowDo> scroll(MultiFamilyScan scan) throws IOException {
        if (scan == null) {
            return null;
        }
        return getScrollData(scan.getTableName(), scan.getSize(), scan.toScan(), scan.getFamilyColumnMap());
    }

    public <T> PageData<T> getEntityPage(SingleFamilyScan scan, Class<T> clazz) throws IOException {

        Map<String, Field> fieldMap = ReflectUtil.getFieldMap(clazz);
        Set<String> columns = fieldMap.keySet();
        scan.setColumns(columns);

        PageData<RowDo> data = page(scan);
        if (data == null || CollectionUtil.isEmpty(data.getContent())) {
            return new PageData<>(scan.getPage(), scan.getSize(), 0L, new ArrayList<>());
        }

        List<T> list = data.getContent().stream().map(rowDo -> {
            Map<String, Map<String, String>> familyKvMap = rowDo.getFamilyKvMap();
            Map<String, String> columnKvMap = familyKvMap.get(scan.getFamily());
            return toEntity(columnKvMap, clazz);
        }).collect(Collectors.toList());
        return new PageData<>(scan.getPage(), scan.getSize(), data.getTotal(), list);
    }

    public <T> ScrollData<T> getEntityScroll(SingleFamilyScan scan, Class<T> clazz) throws IOException {

        Map<String, Field> fieldMap = ReflectUtil.getFieldMap(clazz);
        Set<String> columns = fieldMap.keySet();
        scan.setColumns(columns);

        ScrollData<RowDo> data = scroll(scan);
        if (data == null || CollectionUtil.isEmpty(data.getContent())) {
            return new ScrollData<>(scan.getStartRow(), scan.getStopRow(), null, 0, new ArrayList<>());
        }

        List<T> list = data.getContent().stream().map(rowDo -> {
            Map<String, Map<String, String>> familyKvMap = rowDo.getFamilyKvMap();
            Map<String, String> columnKvMap = familyKvMap.get(scan.getFamily());
            return toEntity(columnKvMap, clazz);
        }).collect(Collectors.toList());
        return new ScrollData<>(data.getStartRow(), data.getStopRow(), data.getScrollRow(), 0, list);
    }

    public <T> ScrollData<T> getEntityScroll(String tableName, String family, String scrollRow, int size,
        Class<T> clazz) throws IOException {
        SingleFamilyScan scan = new SingleFamilyScan();
        scan.setFamily(family)
            .setScrollRow(scrollRow)
            .setTableName(tableName)
            .setSize(size)
            .setReversed(false);
        return getEntityScroll(scan, clazz);
    }

    private PageData<RowDo> getPageData(String tableName, Integer page, Integer size, Scan scan,
        Map<String, Collection<String>> familyColumnMap) throws IOException {
        Table table = getTable(tableName);
        Map<String, RowDo> rowMap = new LinkedHashMap<>(size);
        try {
            int pageIndex = 1;
            byte[] lastRow = null;
            long total = 0L;
            while (true) {
                if (lastRow != null) {
                    scan.withStartRow(lastRow, false);
                }
                ResultScanner rs = table.getScanner(scan);
                Iterator<Result> it = rs.iterator();
                int count = 0;
                while (it.hasNext()) {
                    Result result = it.next();
                    if (page == pageIndex) {
                        RowDo rowDo = getRowFromResult(result, tableName, familyColumnMap);
                        if (rowDo != null) {
                            rowMap.put(rowDo.getRow(), rowDo);
                        }
                    }
                    lastRow = result.getRow();
                    count++;
                }

                pageIndex++;
                rs.close();
                total += count;
                if (count == 0) {
                    break;
                }
            }
            return new PageData<>(page, size, total, rowMap.values());
        } finally {
            recycle(table);
        }
    }

    private ScrollData<RowDo> getScrollData(String tableName, int size, Scan scan,
        Map<String, Collection<String>> familyColumnMap) throws IOException {
        Table table = getTable(tableName);
        ResultScanner scanner = null;
        Map<String, RowDo> rowMap = new LinkedHashMap<>(size);
        try {
            scanner = table.getScanner(scan);
            for (Result result : scanner) {
                RowDo rowDo = getRowFromResult(result, tableName, familyColumnMap);
                if (rowDo != null) {
                    rowMap.put(rowDo.getRow(), rowDo);
                }
            }

            String scrollRow = null;
            if (MapUtil.isNotEmpty(rowMap)) {
                List<String> rows = rowMap.values().stream()
                                          .map(RowDo::getRow)
                                          .collect(Collectors.toList());
                if (scan.isReversed()) {
                    scrollRow = CollectionUtil.min(rows);
                } else {
                    scrollRow = CollectionUtil.max(rows);
                }
            }
            return new ScrollData<>(Bytes.toString(scan.getStartRow()), Bytes.toString(scan.getStopRow()),
                scrollRow, size, rowMap.values());
        } finally {
            IoUtil.close(scanner);
            recycle(table);
        }
    }

    // =====================================================================================
    // 其他操作封装
    // =====================================================================================

    public long incrementColumnValue(String tableName, String row, String family, String column, long amount)
        throws IOException {
        return incrementColumnValue(tableName, row, family, column, amount, Durability.SYNC_WAL);
    }

    public long incrementColumnValue(String tableName, String row, String family, String column, long amount,
        Durability durability) throws IOException {
        if (StrUtil.isBlank(tableName) || StrUtil.isBlank(row) || StrUtil.isBlank(family) || StrUtil.isBlank(column)) {
            return -1L;
        }
        Table table = getTable(tableName);
        try {
            return table.incrementColumnValue(Bytes.toBytes(row), Bytes.toBytes(family), Bytes.toBytes(column), amount,
                durability);
        } finally {
            recycle(table);
        }
    }

    private void batch(String tableName, Collection<? extends Row> list)
        throws IOException, InterruptedException {
        if (StrUtil.isBlank(tableName) || CollectionUtil.isEmpty(list)) {
            return;
        }
        Object[] results = new Object[list.size()];
        Table table = getTable(tableName);
        try {
            table.batch(new ArrayList<>(list), results);
        } finally {
            recycle(table);
        }
    }

    private void recycle(Table table) {
        if (null == table) {
            return;
        }
        IoUtil.close(table);
    }

    private static RowDo getRowFromResult(Result result, String tableName) {

        if (result == null || result.isEmpty()) {
            return null;
        }

        String row = Bytes.toString(result.getRow());
        Map<String, Map<String, ColumnDo>> familyColumnMap = new LinkedHashMap<>(result.size());
        for (Cell cell : result.listCells()) {
            String family = Bytes.toString(CellUtil.cloneFamily(cell));
            if (!familyColumnMap.containsKey(family)) {
                familyColumnMap.put(family, new LinkedHashMap<>(0));
            }
            String column = Bytes.toString(CellUtil.cloneQualifier(cell));
            ColumnDo columnDo = getColumnFromResult(result, tableName, family, column);
            familyColumnMap.get(family).put(column, columnDo);
        }

        Map<String, FamilyDo> familyMap = new LinkedHashMap<>(familyColumnMap.size());
        familyColumnMap.forEach((family, columnMap) -> {
            FamilyDo familyDo = new FamilyDo(tableName, row, family, columnMap);
            familyMap.put(family, familyDo);
        });
        if (MapUtil.isEmpty(familyMap)) {
            return null;
        }
        return new RowDo(tableName, row, familyMap);
    }

    private static RowDo getRowFromResult(Result result, String tableName,
        Map<String, Collection<String>> familyColumnMap) {
        if (MapUtil.isEmpty(familyColumnMap)) {
            return getRowFromResult(result, tableName);
        }
        String row = Bytes.toString(result.getRow());
        Map<String, FamilyDo> familyMap = getFamiliesFromResult(result, tableName, familyColumnMap);
        if (MapUtil.isEmpty(familyMap)) {
            return null;
        }
        return new RowDo(tableName, row, familyMap);
    }

    private static FamilyDo getFamilyFromResult(Result result, String tableName, String family) {

        if (result == null || result.isEmpty()) {
            return null;
        }

        RowDo rowDo = getRowFromResult(result, tableName);
        if (rowDo == null || MapUtil.isEmpty(rowDo.getFamilyMap())) {
            return null;
        }
        return rowDo.getFamilyMap().get(family);
    }

    private static Map<String, FamilyDo> getFamiliesFromResult(Result result, String tableName,
        Map<String, Collection<String>> familyColumnMap) {

        if (result == null || StrUtil.isBlank(tableName) || MapUtil.isEmpty(familyColumnMap)) {
            return new LinkedHashMap<>(0);
        }

        String row = Bytes.toString(result.getRow());
        Map<String, FamilyDo> familyMap = new LinkedHashMap<>(familyColumnMap.size());
        familyColumnMap.forEach((family, columns) -> {
            FamilyDo familyDo;
            if (CollectionUtil.isNotEmpty(columns)) {
                Map<String, ColumnDo> columnMap = new LinkedHashMap<>(columns.size());
                for (String column : columns) {
                    ColumnDo columnDo = getColumnFromResult(result, tableName, family, column);
                    columnMap.put(column, columnDo);
                }
                familyDo = new FamilyDo(tableName, row, family, columnMap);
            } else {
                familyDo = getFamilyFromResult(result, tableName, family);
            }
            familyMap.put(family, familyDo);
        });
        return familyMap;
    }

    private static ColumnDo getColumnFromResult(Result result, String tableName, String family, String column) {

        if (result == null || StrUtil.isBlank(tableName) || StrUtil.isBlank(family) || StrUtil.isBlank(column)) {
            return null;
        }

        Cell cell = result.getColumnLatestCell(Bytes.toBytes(family), Bytes.toBytes(column));
        if (cell == null) {
            return null;
        }
        String row = Bytes.toString(result.getRow());
        String value = Bytes.toString(CellUtil.cloneValue(cell));
        long timestamp = cell.getTimestamp();
        return new ColumnDo(tableName, row, family, timestamp, column, value);
    }

    private static Map<String, ColumnDo> getColumnsFromResult(Result result, String tableName, String family,
        Collection<String> columns) {
        if (CollectionUtil.isEmpty(columns)) {
            RowDo rowDo = getRowFromResult(result, tableName);
            if (rowDo == null) {
                return new LinkedHashMap<>(0);
            }
            return rowDo.getFamilyMap().get(family).getColumnMap();
        }
        Map<String, ColumnDo> columnMap = new LinkedHashMap<>(columns.size());
        for (String column : columns) {
            ColumnDo columnDo = getColumnFromResult(result, tableName, family, column);
            if (columnDo != null) {
                columnMap.put(column, columnDo);
            }
        }
        return columnMap;
    }

    private static <T> T toEntity(Map<String, String> kvMap, Class<T> clazz) {

        if (MapUtil.isEmpty(kvMap)) {
            return null;
        }

        MapUtil.removeNullValue(kvMap);
        T obj;
        try {
            Map<String, Class<?>> typeMap = new LinkedHashMap<>();
            Field[] fields = ReflectUtil.getFields(clazz);
            for (Field f : fields) {
                typeMap.put(f.getName(), f.getType());
            }
            obj = clazz.newInstance();
            for (Map.Entry<String, String> entry : kvMap.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                Class<?> filedType = typeMap.get(key);
                if (filedType != null) {
                    Object fieldObj = JsonUtil.toBean(value, filedType);
                    ReflectUtil.setFieldValue(obj, key, fieldObj);
                }
            }
            return obj;
        } catch (InstantiationException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

}
