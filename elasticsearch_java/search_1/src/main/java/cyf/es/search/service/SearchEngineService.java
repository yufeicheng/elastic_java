package cyf.es.search.service;

import cyf.es.search.domain.TMInfo;
import cyf.es.util.ESHelper;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import net.sf.json.JsonConfig;
import net.sf.json.processors.JsonValueProcessor;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.DateFormatUtils;
import org.apache.commons.lang.time.DateUtils;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.reindex.BulkIndexByScrollResponse;
import org.elasticsearch.index.reindex.DeleteByQueryAction;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.transport.client.PreBuiltTransportClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


@Service
public class SearchEngineService {

    private static Logger LOGGER = LoggerFactory.getLogger(SearchEngineService.class);

    @Autowired
    TMService tmService;

    TransportClient transportClient = null;


    @PostConstruct
    void initLocalResource() {
        Settings settings = Settings.builder()
                // .put("cluster.name", "trademark_1")
                .put("client.transport.sniff", true).build();
        try {
            transportClient = new PreBuiltTransportClient(settings)
                    .addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName(ESHelper.getESIP()), ESHelper.getESPort()));
        } catch (UnknownHostException e) {
            LOGGER.error("ES服务器连接异常", e);
        }
    }

    public TransportClient getTransportClient() {
        return transportClient;
    }


    /**
     * 导入数据 （ 速度太 low）
     *
     * @return
     */
    public boolean allTmApplicantNameIntoEngine() {


        int batchSize = 5000;
        TMInfo tmInfo = new TMInfo();
        tmInfo.setPageCnt(batchSize);
        int recordcount = tmService.selectMaxId();
        int pageNum = recordcount / batchSize;
        pageNum = recordcount % batchSize == 0 ? pageNum : pageNum + 1;

        BulkRequestBuilder bulkRequest = null;
        for (int i = 0; i < pageNum; i++) {
            LOGGER.info("全量更新开始处理：ID[{}]", i);
            tmInfo.setId(i * batchSize + 1);
            try {
                List<TMInfo> tmInfos = tmService.selectByIDRange(tmInfo);
                if (tmInfos.size() == 0) {
                    continue;
                }
                bulkRequest = transportClient.prepareBulk();
                JsonConfig jsonConfig = getJsonConfig();
                TMInfo _tminfo = null;
                for (int j = 0; j < tmInfos.size(); j++) {
                    _tminfo = tmInfos.get(j);
                    try {
                        bulkRequest.add(transportClient.prepareIndex(ESHelper.getTheNameOfIndexForTmdetail(), ESHelper.getTheTypeForTmdetail()).setSource(JSONObject.fromObject(_tminfo, jsonConfig).toString()));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            BulkResponse bulkResponse = bulkRequest.get();
            if (bulkResponse.hasFailures()) {
                LOGGER.error("全量更新处理出现错误，执行下一批次：ID[{}]", i);
            }
            LOGGER.info("全量更新处理结束：ID[{}]", i);
        }
        return true;
    }

    public BoolQueryBuilder getTMinfoBuilder(TMInfo tmInfo) {
        BoolQueryBuilder builder = null;
        if (StringUtils.isNotEmpty(tmInfo.getTmRegNbr())) {
            builder = QueryBuilders.boolQuery();
            List<QueryBuilder> must = builder.must();
            if (tmInfo.getTmClass() != null && tmInfo.getTmClass() > 0) {
                must.add(QueryBuilders.termQuery("tmClass", tmInfo.getTmClass()).boost(10));
            }
            must.add(QueryBuilders.termQuery("tmRegNbr", tmInfo.getTmRegNbr()).boost(10));
            LOGGER.debug(builder.toString());
        }
        return builder;

    }

    public List<TMInfo> query(String keyword) throws IOException {
        BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery();
        //queryBuilder.must(QueryBuilders.termQuery("tmCn", keyword));

      /*  List<QueryBuilder> mustQueryList = queryBuilder.must();
        mustQueryList.add(QueryBuilders.prefixQuery("tmCn", keyword));*/

        List<QueryBuilder> shouldQueryList = queryBuilder.should();
        shouldQueryList.add(QueryBuilders.wildcardQuery("tmCn", "*" + keyword + "*"));

        String nodeName = transportClient.nodeName();
        LOGGER.debug("节点名[{}]", nodeName);
        SearchRequestBuilder searchRequestBuilder = transportClient.prepareSearch(ESHelper.getTheNameOfIndexForTmdetail())
                .setTypes(ESHelper.getTheTypeForTmdetail())
                .setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
                .setQuery(queryBuilder)
                .addSort("tmClass", SortOrder.ASC)
                .addSort("_score", SortOrder.DESC);
        // .setFrom(tmInfo.getRowStart()).setSize(tmInfo.getPageCnt());
        SearchResponse searchResponse = searchRequestBuilder.get();
        SearchHits searchHits = searchResponse.getHits();
        LOGGER.debug("DSL:[{}]", searchRequestBuilder.toString());
        long total = searchHits.totalHits();
        LOGGER.debug("总数量：[{}]", total);
        List<TMInfo> tmInfos = new ArrayList<>();
        for (SearchHit searchHit : searchHits) {
            System.out.println(searchHit.getSource());
            TMInfo tmInfo = Jackson2ObjectMapperBuilder.json().build().readValue(searchHit.getSourceAsString(), TMInfo.class);
            tmInfos.add(tmInfo);
        }
        return tmInfos;
    }


    public boolean delByTMId(BoolQueryBuilder boolQueryBuilder) {
        BulkIndexByScrollResponse response =
                DeleteByQueryAction.INSTANCE.newRequestBuilder(transportClient)
                        .filter(boolQueryBuilder)
                        .source(ESHelper.getTheNameOfIndexForTmdetail())
                        .get();
        return response.getDeleted() > 0 ? true : false;
    }


    public boolean singleTMIntoEngine(Object object) {
        BulkRequestBuilder bulkRequest = transportClient.prepareBulk();
        JsonConfig jsonConfig = getJsonConfig();
        try {
            if (object.getClass().equals(TMInfo.class)) {
                bulkRequest.add(transportClient.prepareIndex(ESHelper.getTheNameOfIndexForTmdetail(), ESHelper.getTheTypeForTmdetail()).setSource(JSONObject.fromObject(object, jsonConfig).toString()));

            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        BulkResponse bulkResponse = bulkRequest.get();
        if (bulkResponse.hasFailures()) {
            LOGGER.error("ES插入失败");
            return false;
        }
        return true;
    }


    /**
     * 测试
     *
     * @return
     */

    public void del() {
        try {
            DeleteResponse deleteResponse = transportClient.prepareDelete(ESHelper.getTheNameOfIndexForTmdetail(), ESHelper.getTheTypeForTmdetail(), "AVwux7iiKEZAdc1qUqEP").get();
            System.out.println();
        } catch (Exception e) {
            LOGGER.error("删除失败", e);
        }

    }

    /**
     * 局部更新字段
     *
     * @throws IOException
     */
    public void update() throws IOException {
        XContentBuilder builder = XContentFactory.jsonBuilder()
                .startObject()
                .field("pageCnt", 30)
                .endObject();
        UpdateResponse response = transportClient.prepareUpdate(ESHelper.getTheNameOfIndexForTmdetail(), ESHelper.getTheTypeForTmdetail(), "AVwux1rUKEZAdc1qUgAR").setDoc(builder).get();
        System.out.println(response.getVersion());
    }


    private JsonConfig getJsonConfig() {
        JsonConfig jsonConfig = new JsonConfig();
        jsonConfig.registerJsonValueProcessor(java.util.Date.class, new JsonValueProcessor() {
            private final String format = "yyyy-MM-dd";

            public Object processObjectValue(String key, Object value, JsonConfig arg2) {
                if (value == null)
                    return "1970-01-01";
                if (value instanceof java.util.Date) {
                    String str = new SimpleDateFormat(format).format((java.util.Date) value);
                    return str;
                }
                return value.toString();
            }

            public Object processArrayValue(Object value, JsonConfig arg1) {
                return null;
            }

        });
        jsonConfig.registerJsonValueProcessor(String.class, new JsonValueProcessor() {
            private final String format = "yyyy-MM-dd";

            public Object processObjectValue(String key, Object value, JsonConfig arg2) {

                if (key.equals("appDate") || key.equals("interRegDate") || key.equals("trialDate")) {
                    if (value == null || StringUtils.trimToEmpty((String) value).length() == 0)
                        return "1970-01-01";
                    try {
                        return DateFormatUtils.format(DateUtils.parseDate(StringUtils.trimToEmpty((String) value), new String[]{"yyyy-M-d", "yyyyMM-dd", "yyyyMMdd", "yyyy-MMdd", "yyyy-MM-dd", "MM d yyyy"}), format);
                    } catch (ParseException e) {
                        LOGGER.error(e.getLocalizedMessage());
                        return "1970-01-01";
                    }
                }

                if (value == null)
                    return "";
                return StringUtils.trimToEmpty((String) value);
            }

            public Object processArrayValue(Object value, JsonConfig arg1) {
                return null;
            }

        });
        return jsonConfig;
    }

    /**
     * 多线程测试（ 效果不理想，会有重复数据，为进行优化，不使用）
     *
     * @return
     */
    public boolean IntoEngineByThreadPool() throws InterruptedException {

        int batchSize = 5000;
        TMInfo tmInfo = new TMInfo();
        tmInfo.setPageCnt(batchSize);
        int recordcount = tmService.selectMaxId();
        int pageNum = recordcount / batchSize;
        pageNum = recordcount % batchSize == 0 ? pageNum : pageNum + 1;
        final BulkRequestBuilder[] bulkRequest = new BulkRequestBuilder[pageNum];
        final CountDownLatch begin = new CountDownLatch(1);
        final CountDownLatch end = new CountDownLatch(pageNum);
        final ExecutorService newFixThreadPool = Executors.newFixedThreadPool(10);
        //ExecutorService newFixThreadPool= Executors.newCachedThreadPool();
        for (int i = 0; i < pageNum; i++) {
            final int finalI = i;
            tmInfo.setId(finalI * batchSize + 1);
            final List<TMInfo> tmInfos = tmService.selectByIDRange(tmInfo);
            newFixThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        begin.await();
                        LOGGER.info("全量更新开始处理：ID[{}]", finalI);
                        try {
                            bulkRequest[finalI] = transportClient.prepareBulk();
                            JsonConfig jsonConfig = getJsonConfig();
                            TMInfo _tminfo = null;
                            for (int j = 0; j < tmInfos.size(); j++) {
                                _tminfo = tmInfos.get(j);
                                try {
                                    //bulkRequest[0].add(transportClient.prepareIndex(ESHelper.getTheNameOfIndexForTmdetail(), ESHelper.getTheTypeForTmdetail()).setSource(JSONObject.fromObject(_tminfo, jsonConfig).toString())).execute().actionGet();
                                    bulkRequest[finalI].add(transportClient.prepareIndex(ESHelper.getTheNameOfIndexForTmdetail(), ESHelper.getTheTypeForTmdetail()).setSource(JSONObject.fromObject(_tminfo, jsonConfig).toString()));
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        // BulkResponse bulkResponse = bulkRequest[finalI].get();
                      /*  if (bulkResponse.hasFailures()) {
                            LOGGER.error("全量更新处理出现错误，执行下一批次：ID[{}]", finalI);
                        }*/
                        bulkRequest[finalI].execute().actionGet();
                        LOGGER.info("全量更新处理结束：ID[{}]", finalI);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } finally {
                        end.countDown();
                    }

                }
            });
        }
        long startTime = System.currentTimeMillis();
        System.out.println("开始时间：=====" + new Timestamp(System.currentTimeMillis()));
        begin.countDown();
        end.await();
        System.out.println("结束时间：======" + new Timestamp(System.currentTimeMillis()));
        long endTime = System.currentTimeMillis();
//        System.out.println((endTime-startTime)/1000);
        newFixThreadPool.shutdown();
        LOGGER.info("耗费时间：TIME[{}]", endTime - startTime);
        return true;
    }

    /**
     * 通过生成 .json 文件 和 .sh ，在 服务器上手动执行 .sh （注意结尾 ; 的添加 ，不然执行错误）来完成数据导入，测试效果理想
     * <p>
     * curl localhost:9200/cyf_4/search_4/_bulk?pretty --data-binary @1496806632345.json;
     * curl localhost:9200/cyf_4/search_4/_bulk?pretty --data-binary @"/usr/local/elasticsearch/cyf_data/1496735737907.json";
     *
     * @throws IOException
     */
    public void getDate() throws IOException {
        int batchSize = 5000;
        TMInfo tmInfo = new TMInfo();
        tmInfo.setPageCnt(batchSize);

        int recordcount = tmService.selectMaxId();
        /*int pageNum = recordcount / batchSize;
        pageNum = recordcount % batchSize == 0 ? pageNum : pageNum + 1;*/

        int pageNum = 100;
        StringBuilder sb = new StringBuilder();
        long count = 0;
        for (int i = 0; i < pageNum; i++) {
            tmInfo.setId(i * batchSize + 1);
            List<TMInfo> tmInfos = tmService.selectByIDRange(tmInfo);
            count += tmInfos.size();
            JsonConfig jsonConfig = getJsonConfig();
            TMInfo _tminfo = null;
            for (int j = 0; j < tmInfos.size(); j++) {
                _tminfo = tmInfos.get(j);
                sb.append("{\"index\" : { \"_index\" : \"cyf_4\", \"_type\" : \"search_4\"}}\\n").append("\r\n").append(JSONObject.fromObject(_tminfo, jsonConfig).toString()).append("\r\n");
            }
            if (count >= 20000) {
                getFile(sb);
                //sb.delete(0, sb.length());
                sb.setLength(0);
                count = 0;
            }
            if (i == pageNum - 1) {
                getFile(sb);
            }

        }
        getSH("D:/Cheng/es_cyf_data");
        if (CollectionUtils.isNotEmpty(shList)) {
            StringBuilder bf = new StringBuilder();
            for (String s : shList) {
                //@"/usr/local/elasticsearch/cyf_data/1496735737907.json"
                bf.append("curl localhost:9200/cyf_4/search_4/_bulk?pretty --data-binary @").append(s + ";").append("\r\n");
            }
            getSHFile(bf);
        }
        // return sb.toString();
    }


    public void getFile(StringBuilder sb) throws IOException {
        File file = new File("D:/Cheng/es_cyf_data/" + System.currentTimeMillis() + ".json");
        if (!file.exists()) {
            file.createNewFile();
        }
        PrintWriter pw = new PrintWriter(file, "utf-8");
        pw.print(sb.toString());
        pw.flush();
    }

    public void getSHFile(StringBuilder sb) throws IOException {
        File file = new File("D:/Cheng/es_cyf_sh/" + System.currentTimeMillis() + ".sh");
        if (!file.exists()) {
            file.createNewFile();
        }
        PrintWriter pw = new PrintWriter(file, "utf-8");
        pw.print(sb.toString());
        pw.flush();
    }

    List<String> shList = new ArrayList<>();

    public void getSH(String path) {
        File f = new File(path);
        if (f.isDirectory()) {
            File[] listFiles = f.listFiles();
            if (null != listFiles && listFiles.length > 0) {
                for (File c : listFiles) {
                    if (c.isDirectory()) {
                        getSH(c.getAbsolutePath());
                    } else {
                        System.out.println(c.getName());
                        if (c.length() > 0) {

                            shList.add(c.getName());
                        }
                    }
                }
            }
        } else {
            System.out.println(f.getName());
            if (f.length() > 0) {
                shList.add(f.getName());
            }
        }
    }

}

