package com.transdb.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transdb.common.BusinessException;
import com.transdb.common.ErrorCode;
import com.transdb.domain.Segment;
import com.transdb.dto.ReindexStatusVO;
import com.transdb.repository.SegmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReindexService {

    public record ReindexState(String state, long indexed, long total,
                               Instant startedAt, Instant finishedAt, String error) {
    }

    private final SegmentRepository segmentRepository;
    private final EsDocumentAssembler assembler;
    private final EsIndexAdminService esIndexAdminService;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    /**
     * volatile 引用整体替换（无 CAS 需求）：HTTP 线程写 RUNNING、reindex 线程推进/DONE/FAILED、
     * status() 读取，volatile 保证跨线程可见。
     */
    private volatile ReindexState state =
            new ReindexState("IDLE", 0, 0, null, null, null);

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "es-reindex");
                t.setDaemon(true);
                return t;
            });

    public ReindexStatusVO start() {
        ReindexState current = state;
        if ("RUNNING".equals(current.state())) {
            throw BusinessException.of(ErrorCode.REINDEX_ALREADY_RUNNING);
        }
        long total = segmentRepository.count();
        ReindexState running = new ReindexState("RUNNING", 0, total, Instant.now(), null, null);
        state = running;
        executor.execute(this::run);
        return toVo(running);
    }

    public ReindexStatusVO status() {
        return toVo(state);
    }

    private void run() {
        String newIndex = null;
        boolean swapped = false;
        try {
            List<String> oldIndices = esIndexAdminService.currentAliasIndices();
            newIndex = "segments_reindex_" + System.currentTimeMillis();
            esIndexAdminService.createIndex(newIndex);

            long indexed = 0;
            long lastId = 0L;
            while (true) {
                final long cursor = lastId;
                List<Map<String, Object>> docs = transactionTemplate.execute(tx -> {
                    List<Segment> batch = segmentRepository
                            .findTop500ByIdGreaterThanOrderByIdAsc(cursor);
                    return batch.stream().map(assembler::toDoc).toList();
                });
                if (docs == null || docs.isEmpty()) {
                    break;
                }
                StringBuilder ndjson = new StringBuilder();
                for (Map<String, Object> doc : docs) {
                    ndjson.append("{\"index\":{\"_index\":\"").append(newIndex)
                            .append("\",\"_id\":\"").append(doc.get("segment_id")).append("\"}}\n")
                            .append(objectMapper.writeValueAsString(doc)).append('\n');
                }
                Request bulk = new Request("POST", "/_bulk");
                bulk.setJsonEntity(ndjson.toString());
                bulk.addParameter("refresh", "false");
                Response bulkResp = restClient.performRequest(bulk);
                JsonNode bulkResult = objectMapper.readTree(
                        EntityUtils.toString(bulkResp.getEntity(), StandardCharsets.UTF_8));
                BulkResponseGuard.requireNoErrors(bulkResult, docs.size());
                indexed += docs.size();
                lastId = lastDocId(docs);
                ReindexState cur = state;
                state = new ReindexState("RUNNING", indexed, cur.total(),
                        cur.startedAt(), null, null);
                if (docs.size() < 500) {
                    break;
                }
            }

            esIndexAdminService.swapAlias(oldIndices, newIndex);
            swapped = true;
            for (String old : oldIndices) {
                if (!old.equals(newIndex)) {
                    esIndexAdminService.deleteIndex(old);
                }
            }
            ReindexState cur = state;
            state = new ReindexState("DONE", indexed, cur.total(),
                    cur.startedAt(), Instant.now(), null);
            log.info("ES 全量重建完成：{} 条，索引 {}", indexed, newIndex);
        } catch (Exception e) {
            ReindexState cur = state;
            state = new ReindexState("FAILED", cur.indexed(), cur.total(),
                    cur.startedAt(), Instant.now(), e.getMessage());
            log.error("ES 全量重建失败", e);
            // 别名尚未切换：新建索引已成孤儿，尽力删除，避免残留空索引
            if (!swapped && newIndex != null) {
                try {
                    esIndexAdminService.deleteIndex(newIndex);
                } catch (Exception cleanupFailure) {
                    log.warn("清理失败索引 {} 异常: {}", newIndex, cleanupFailure.getMessage());
                }
            }
        }
    }

    private Long lastDocId(List<Map<String, Object>> docs) {
        return Long.parseLong((String) docs.get(docs.size() - 1).get("segment_id"));
    }

    /** /_bulk 返回 HTTP 200 仍可能逐项失败（errors=true），数据不完整时必须判失败而非继续切换别名。 */

    private ReindexStatusVO toVo(ReindexState s) {
        return new ReindexStatusVO(s.state(), s.indexed(), s.total(), s.startedAt(), s.finishedAt(), s.error());
    }
}
