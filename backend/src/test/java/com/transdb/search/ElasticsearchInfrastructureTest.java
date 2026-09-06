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

class ElasticsearchInfrastructureTest extends AbstractIntegrationTest {

    @Autowired RestClient esClient;

    @Test
    void esReachableWithPluginsInstalled() throws Exception {
        Response health = esClient.performRequest(new Request("GET", "/_cluster/health"));
        assertThat(health.getStatusLine().getStatusCode()).isEqualTo(200);

        Response plugins = esClient.performRequest(new Request("GET", "/_cat/plugins?format=json"));
        String body = EntityUtils.toString(plugins.getEntity(), StandardCharsets.UTF_8);
        assertThat(body).contains("analysis-ik");
        assertThat(body).contains("analysis-pinyin");
    }
}
