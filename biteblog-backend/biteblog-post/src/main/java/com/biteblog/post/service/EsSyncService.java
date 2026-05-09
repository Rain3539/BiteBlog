package com.biteblog.post.service;

import com.biteblog.post.dto.EsPostDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EsSyncService {

    private final ElasticsearchOperations esOps;

    public void savePostToEs(EsPostDocument doc) {
        esOps.save(doc);
    }

    public void deletePostFromEs(Long noteId) {
        esOps.delete(noteId.toString(), EsPostDocument.class);
    }

    public List<EsPostDocument> search(String keyword, int page, int size) {
        Criteria criteria = new Criteria("title").contains(keyword)
                .or("content").contains(keyword)
                .or("shopName").contains(keyword);
        CriteriaQuery query = new CriteriaQuery(criteria);
        query.setPageable(PageRequest.of(page - 1, size));

        SearchHits<EsPostDocument> hits = esOps.search(query, EsPostDocument.class);
        return hits.stream()
                .map(SearchHit::getContent)
                .collect(Collectors.toList());
    }

    public long countSearchResults(String keyword) {
        Criteria criteria = new Criteria("title").contains(keyword)
                .or("content").contains(keyword)
                .or("shopName").contains(keyword);
        CriteriaQuery query = new CriteriaQuery(criteria);
        return esOps.count(query, EsPostDocument.class);
    }
}
