#!/bin/bash
# ================================================================
# Elasticsearch 索引初始化脚本
# 使用方式: bash init-indices.sh
# 前置条件: ES 已启动 (localhost:9200)
# ================================================================

ES_URL="http://localhost:9200"

echo "=== 等待 ES 就绪 ==="
until curl -s "$ES_URL/_cluster/health" > /dev/null 2>&1; do
  echo "ES 未就绪，等待 5 秒..."
  sleep 5
done
echo "ES 已就绪"

# ==================== post_index ====================
echo "创建 post_index ..."
curl -s -X PUT "$ES_URL/post_index" -H 'Content-Type: application/json' -d '{
  "mappings": {
    "properties": {
      "postId":       { "type": "keyword" },
      "user_id":      { "type": "keyword" },
      "title":        { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart", "fields": { "keyword": { "type": "keyword" } } },
      "content":      { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
      "image_urls":   { "type": "keyword" },
      "store_name":   { "type": "text", "analyzer": "ik_max_word", "fields": { "keyword": { "type": "keyword" } } },
      "location":     { "type": "geo_point" },
      "tags":         { "type": "keyword" },
      "score_color":  { "type": "integer" },
      "score_smell":  { "type": "integer" },
      "score_taste":  { "type": "integer" },
      "like_count":   { "type": "long" },
      "collect_count":{ "type": "long" },
      "comment_count":{ "type": "long" },
      "view_count":   { "type": "long" },
      "hot_score":    { "type": "double" },
      "status":       { "type": "integer" },
      "created_at":   { "type": "date" }
    }
  }
}'
echo ""

# ==================== user_index ====================
echo "创建 user_index ..."
curl -s -X PUT "$ES_URL/user_index" -H 'Content-Type: application/json' -d '{
  "mappings": {
    "properties": {
      "userId":         { "type": "keyword" },
      "username":       { "type": "keyword" },
      "phone":          { "type": "keyword" },
      "password_hash":  { "type": "keyword" },
      "avatar":         { "type": "keyword" },
      "bio":            { "type": "text" },
      "fans_count":     { "type": "long" },
      "follow_count":   { "type": "long" },
      "is_big_v":       { "type": "boolean" },
      "status":         { "type": "integer" },
      "created_at":     { "type": "date" }
    }
  }
}'
echo ""

# ==================== follow_index ====================
echo "创建 follow_index ..."
curl -s -X PUT "$ES_URL/follow_index" -H 'Content-Type: application/json' -d '{
  "mappings": {
    "properties": {
      "user_id":         { "type": "keyword" },
      "follow_user_id":  { "type": "keyword" },
      "created_at":      { "type": "date" }
    }
  }
}'
echo ""

# ==================== comment_index ====================
echo "创建 comment_index ..."
curl -s -X PUT "$ES_URL/comment_index" -H 'Content-Type: application/json' -d '{
  "mappings": {
    "properties": {
      "commentId":  { "type": "keyword" },
      "post_id":    { "type": "keyword" },
      "user_id":    { "type": "keyword" },
      "parent_id":  { "type": "keyword" },
      "content":    { "type": "text", "analyzer": "ik_max_word" },
      "status":     { "type": "integer" },
      "created_at": { "type": "date" }
    }
  }
}'
echo ""

# ==================== behavior_index ====================
echo "创建 behavior_index ..."
curl -s -X PUT "$ES_URL/behavior_index" -H 'Content-Type: application/json' -d '{
  "mappings": {
    "properties": {
      "eventId":     { "type": "keyword" },
      "user_id":     { "type": "keyword" },
      "post_id":     { "type": "keyword" },
      "event_type":  { "type": "keyword" },
      "tags":        { "type": "keyword" },
      "dwell_time":  { "type": "integer" },
      "source":      { "type": "keyword" },
      "created_at":  { "type": "date" }
    }
  }
}'
echo ""

# ==================== user_profile_index ====================
echo "创建 user_profile_index ..."
curl -s -X PUT "$ES_URL/user_profile_index" -H 'Content-Type: application/json' -d '{
  "mappings": {
    "properties": {
      "user_id":           { "type": "keyword" },
      "interest_tags":     { "type": "keyword" },
      "tag_weights":       { "type": "object" },
      "active_level":      { "type": "integer" },
      "last_update_time":  { "type": "date" }
    }
  }
}'
echo ""

# ==================== item_sim_index ====================
echo "创建 item_sim_index ..."
curl -s -X PUT "$ES_URL/item_sim_index" -H 'Content-Type: application/json' -d '{
  "mappings": {
    "properties": {
      "post_id":          { "type": "keyword" },
      "related_post_id":  { "type": "keyword" },
      "sim_score":        { "type": "double" },
      "reason_tags":      { "type": "keyword" },
      "updated_at":       { "type": "date" }
    }
  }
}'
echo ""

# ==================== notification_index ====================
echo "创建 notification_index ..."
curl -s -X PUT "$ES_URL/notification_index" -H 'Content-Type: application/json' -d '{
  "mappings": {
    "properties": {
      "notifyId":    { "type": "keyword" },
      "to_user_id":  { "type": "keyword" },
      "from_user_id":{ "type": "keyword" },
      "type":        { "type": "keyword" },
      "post_id":     { "type": "keyword" },
      "is_read":     { "type": "boolean" },
      "created_at":  { "type": "date" }
    }
  }
}'
echo ""

# ==================== rank_index ====================
echo "创建 rank_index ..."
curl -s -X PUT "$ES_URL/rank_index" -H 'Content-Type: application/json' -d '{
  "mappings": {
    "properties": {
      "rankId":        { "type": "keyword" },
      "post_id":       { "type": "keyword" },
      "rank_type":     { "type": "keyword" },
      "rank_date":     { "type": "date" },
      "hot_score":     { "type": "double" },
      "like_count":    { "type": "long" },
      "collect_count": { "type": "long" },
      "comment_count": { "type": "long" },
      "view_count":    { "type": "long" }
    }
  }
}'
echo ""

echo "=== 全部索引创建完成 ==="
