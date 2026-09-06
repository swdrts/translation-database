package com.transdb.search;

import com.transdb.AbstractIntegrationTest;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class EsIndexAdminServiceTest extends AbstractIntegrationTest {

    @Autowired RestClient esClient;
    @Autowired EsIndexAdminService esIndexAdminService;

    @Test
    void ensureIndicesCreatesAliasAndAnalyzers() throws Exception {
        esIndexAdminService.ensureIndices();
        esIndexAdminService.ensureIndices(); // 幂等：第二次调用不得报错

        Response head = esClient.performRequest(new Request("GET", "/segments"));
        assertThat(head.getStatusLine().getStatusCode()).isEqualTo(200);

        String mapping = EntityUtils.toString(
                esClient.performRequest(new Request("GET", "/segments/_mapping")).getEntity(),
                StandardCharsets.UTF_8);
        assertThat(mapping).contains("ik_max_word").contains("pinyin_analyzer").contains("completion");

        // 中文分词生效
        Request analyzeIk = new Request("POST", "/segments/_analyze");
        analyzeIk.setJsonEntity("{\"analyzer\":\"ik_smart\",\"text\":\"学而时习之\"}");
        String ikResult = EntityUtils.toString(
                esClient.performRequest(analyzeIk).getEntity(), StandardCharsets.UTF_8);
        assertThat(ikResult).contains("token");

        // 拼音分词生效：全拼与首字母
        Request analyzePy = new Request("POST", "/segments/_analyze");
        analyzePy.setJsonEntity("{\"analyzer\":\"pinyin_analyzer\",\"text\":\"道德经\"}");
        String pyResult = EntityUtils.toString(
                esClient.performRequest(analyzePy).getEntity(), StandardCharsets.UTF_8);
        assertThat(pyResult).contains("daodejing").contains("ddj");
    }
}
