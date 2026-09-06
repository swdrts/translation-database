package com.transdb.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transdb.repository.SegmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EsSyncService {

    public enum Op { UPSERT, DELETE }

    private final SegmentRepository segmentRepository;
    private final EsDocumentAssembler assembler;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final EsProperties esProperties;
    private final TransactionTemplate transactionTemplate;

    /** 直接执行，不吞异常；调用方（监听器/重试队列）负责失败兜底。 */
    public void applyNow(long segmentId, Op op) {
        if (!esProperties.enabled()) {
            return;
        }
        try {
            switch (op) {
                case UPSERT -> upsert(segmentId);
                case DELETE -> delete(segmentId);
            }
        } catch (Exception e) {
            throw new IllegalStateException("ES 同步失败 segmentId=" + segmentId + " op=" + op, e);
        }
    }

    private void upsert(long segmentId) throws Exception {
        // findByIdForSync：tags 为 LAZY 集合，异步线程组装文档时无 Session，须 fetch join 一并取回
        var segment = segmentRepository.findByIdForSync(segmentId);
        if (segment == null) {
            delete(segmentId);
            return;
        }
        Map<String, Object> doc = assembler.toDoc(segment);
        Request request = new Request("PUT", "/segments/_doc/" + segmentId);
        request.setJsonEntity(objectMapper.writeValueAsString(doc));
        restClient.performRequest(request);
    }

    private void delete(long segmentId) throws Exception {
        restClient.performRequest(new Request("DELETE", "/segments/_doc/" + segmentId));
    }

    /** 对账：扫描近 windowHours 变更的句段，ES 缺失或版本陈旧则重建文档。返回修复数。 */
    public long reconcile(long windowHours) {
        if (!esProperties.enabled()) {
            return 0;
        }
        long repaired = 0;
        Long lastId = 0L;
        Instant since = Instant.now().minusSeconds(windowHours * 3600);
        while (true) {
            // 每批独立短事务读取：持久化上下文随批关闭，不在整轮扫描（含 ES I/O）期间占用 DB 连接
            final Long cursor = lastId;
            var batch = transactionTemplate.execute(tx ->
                    segmentRepository.findByUpdatedAtGreaterThanEqualAndIdGreaterThan(
                            since, cursor, PageRequest.of(0, 500, Sort.by("id"))));
            if (batch == null || batch.isEmpty()) {
                break;
            }
            for (var s : batch) {
                lastId = s.getId();
                try {
                    if (isMissingOrStale(s.getId(), s.getVersion())) {
                        upsert(s.getId());
                        repaired++;
                    }
                } catch (Exception e) {
                    log.warn("对账修复失败 segmentId={}: {}", s.getId(), e.getMessage());
                }
            }
            if (batch.getNumberOfElements() < 500) {
                break;
            }
        }
        if (repaired > 0) {
            log.info("ES 对账完成，修复 {} 条", repaired);
        }
        return repaired;
    }

    /**
     * 仅按 version 判断（对账任务在扫描批次里已带出实体的最新 version，
     * brief 原稿中 updated_at 的二次查库属冗余，按任务约定简化，详见 task-4 报告）。
     */
    private boolean isMissingOrStale(long segmentId, Integer version) throws java.io.IOException {
        try {
            Response resp = restClient.performRequest(new Request("GET", "/segments/_doc/" + segmentId));
            String body = new String(resp.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode source = objectMapper.readTree(body).path("_source");
            return version == null || source.path("version").asInt(-1) != version;
        } catch (org.elasticsearch.client.ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() == 404) {
                return true;
            }
            throw e;
        }
    }
}
