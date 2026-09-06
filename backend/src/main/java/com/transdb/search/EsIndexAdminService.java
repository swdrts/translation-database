package com.transdb.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EsIndexAdminService {

    public static final String ALIAS = "segments";
    public static final String DEFAULT_INDEX = "segments_v1";
    private static final String INDEX_DEFINITION = "elasticsearch/segments-index.json";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /** 幂等创建：别名存在则跳过；ES 不可达仅告警不抛出（应用可无 ES 启动）。 */
    public void ensureIndices() {
        try {
            if (!aliasExists()) {
                createIndex(DEFAULT_INDEX);
                bindAlias(DEFAULT_INDEX);
                log.info("已创建 ES 索引 {} 并绑定别名 {}", DEFAULT_INDEX, ALIAS);
            }
        } catch (Exception e) {
            log.warn("ES 索引初始化失败（搜索将进入降级模式，稍后可通过 /api/v1/admin/reindex 重建）: {}", e.getMessage());
        }
    }

    public void createIndex(String name) {
        Request request = new Request("PUT", "/" + name);
        request.setJsonEntity(readIndexDefinition());
        raw(request);
    }

    public void bindAlias(String index) {
        Request request = new Request("POST", "/_aliases");
        request.setJsonEntity("{\"actions\":[{\"add\":{\"index\":\"" + index + "\",\"alias\":\"" + ALIAS + "\"}}]}");
        raw(request);
    }

    public List<String> currentAliasIndices() {
        JsonNode node = raw(new Request("GET", "/_alias/" + ALIAS));
        List<String> names = new ArrayList<>();
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            names.add(it.next());
        }
        return names;
    }

    public void swapAlias(List<String> oldIndices, String newIndex) {
        StringBuilder actions = new StringBuilder("[");
        for (String old : oldIndices) {
            actions.append("{\"remove\":{\"index\":\"").append(old).append("\",\"alias\":\"").append(ALIAS).append("\"}},");
        }
        actions.append("{\"add\":{\"index\":\"").append(newIndex).append("\",\"alias\":\"").append(ALIAS).append("\"}}]");
        Request request = new Request("POST", "/_aliases");
        request.setJsonEntity("{\"actions\":" + actions + "}");
        raw(request);
    }

    public void deleteIndex(String name) {
        raw(new Request("DELETE", "/" + name));
    }

    private boolean aliasExists() throws IOException {
        Response response = restClient.performRequest(new Request("HEAD", "/" + ALIAS));
        return response.getStatusLine().getStatusCode() == 200;
    }

    private String readIndexDefinition() {
        try {
            return new String(new ClassPathResource(INDEX_DEFINITION).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 ES 索引定义失败: " + INDEX_DEFINITION, e);
        }
    }

    private JsonNode raw(Request request) {
        try {
            Response response = restClient.performRequest(request);
            String body = response.getEntity() == null ? "{}"
                    : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            return objectMapper.readTree(body.isEmpty() ? "{}" : body);
        } catch (Exception e) {
            throw new IllegalStateException("ES 请求失败: " + request.getMethod() + " " + request.getEndpoint(), e);
        }
    }
}
