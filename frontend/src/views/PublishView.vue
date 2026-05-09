<template>
  <div class="publish-page">
    <h3>发布探店笔记</h3>

    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="publish-form">
      <!-- 图片上传 -->
      <el-form-item label="图片" prop="imageUrls">
        <div class="upload-area">
          <div v-for="(url, i) in form.imageUrls" :key="i" class="upload-item">
            <img :src="url" class="upload-preview" />
            <el-icon class="upload-remove" @click="removeImage(i)"><CircleClose /></el-icon>
            <span v-if="i === 0" class="cover-tag">封面</span>
          </div>
          <el-upload
            v-if="form.imageUrls.length < 9"
            :auto-upload="false"
            :show-file-list="false"
            accept="image/*"
            :on-change="handleImageChange"
            class="upload-trigger"
          >
            <el-icon size="32"><Plus /></el-icon>
          </el-upload>
        </div>
      </el-form-item>

      <!-- 标题 -->
      <el-form-item label="标题" prop="title">
        <el-input v-model="form.title" maxlength="100" show-word-limit placeholder="给笔记起个吸引人的标题" />
      </el-form-item>

      <!-- 正文 -->
      <el-form-item label="正文" prop="content">
        <el-input
          v-model="form.content"
          type="textarea"
          :rows="8"
          maxlength="5000"
          show-word-limit
          placeholder="分享你的探店体验吧..."
        />
      </el-form-item>

      <!-- 店铺信息 -->
      <el-row :gutter="20">
        <el-col :span="12">
          <el-form-item label="店铺名称">
            <el-input v-model="form.shopName" placeholder="如：老王烧烤" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="店铺地址">
            <el-input v-model="form.address" placeholder="如：武汉市洪山区珞喻路1037号" />
          </el-form-item>
        </el-col>
      </el-row>

      <!-- 评分 -->
      <el-form-item label="评分">
        <div class="scores">
          <div class="score-item">
            <span class="score-label">环境</span>
            <el-rate v-model="form.scoreColor" :max="5" />
          </div>
          <div class="score-item">
            <span class="score-label">卫生</span>
            <el-rate v-model="form.scoreSmell" :max="5" />
          </div>
          <div class="score-item">
            <span class="score-label">口味</span>
            <el-rate v-model="form.scoreTaste" :max="5" />
          </div>
        </div>
      </el-form-item>

      <!-- 提交 -->
      <el-form-item>
        <el-button type="primary" size="large" :loading="publishing" @click="handlePublish" class="submit-btn">
          {{ publishing ? '发布中...' : '发布笔记' }}
        </el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus, CircleClose } from '@element-plus/icons-vue'
import { uploadImage, publishPost } from '../api/post'

const router = useRouter()
const formRef = ref(null)
const publishing = ref(false)

const form = reactive({
  title: '',
  content: '',
  shopName: '',
  address: '',
  scoreColor: 0,
  scoreSmell: 0,
  scoreTaste: 0,
  imageUrls: []
})

const rules = {
  title: [
    { required: true, message: '请输入标题', trigger: 'blur' },
    { max: 100, message: '标题最长100字', trigger: 'blur' }
  ],
  content: [
    { required: true, message: '请输入正文', trigger: 'blur' }
  ]
}

async function handleImageChange(file) {
  try {
    const res = await uploadImage(file.raw)
    form.imageUrls.push(res.data)
  } catch {
    ElMessage.error('图片上传失败')
  }
}

function removeImage(index) {
  form.imageUrls.splice(index, 1)
}

async function handlePublish() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  publishing.value = true
  try {
    const res = await publishPost({
      title: form.title,
      content: form.content,
      shopName: form.shopName || null,
      address: form.address || null,
      longitude: null,
      latitude: null,
      scoreColor: form.scoreColor || 0,
      scoreSmell: form.scoreSmell || 0,
      scoreTaste: form.scoreTaste || 0,
      imageUrls: form.imageUrls
    })
    ElMessage.success('发布成功')
    router.push(`/post/${res.data.postId}`)
  } catch {
    // 错误已在拦截器中提示
  } finally {
    publishing.value = false
  }
}
</script>

<style scoped>
.publish-page {
  max-width: 700px;
  margin: 0 auto;
  padding: 24px 0;
}

h3 {
  margin: 0 0 24px;
  font-size: 20px;
  color: #303133;
}

.publish-form {
  background: #fff;
  padding: 32px;
  border-radius: 12px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
}

.upload-area {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.upload-item {
  position: relative;
  width: 100px;
  height: 100px;
  border-radius: 8px;
  overflow: hidden;
}

.upload-preview {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.upload-remove {
  position: absolute;
  top: 2px;
  right: 2px;
  color: #f56c6c;
  cursor: pointer;
  font-size: 18px;
  background: rgba(255, 255, 255, 0.9);
  border-radius: 50%;
}

.cover-tag {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  text-align: center;
  background: rgba(64, 158, 255, 0.8);
  color: #fff;
  font-size: 11px;
  padding: 2px 0;
}

.upload-trigger {
  width: 100px;
  height: 100px;
  border: 2px dashed #dcdfe6;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: #c0c4cc;
  transition: border-color 0.3s;
}

.upload-trigger:hover {
  border-color: #409eff;
}

.scores {
  display: flex;
  gap: 24px;
  flex-wrap: wrap;
}

.score-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.score-label {
  font-size: 14px;
  color: #606266;
  min-width: 28px;
}

.submit-btn {
  width: 100%;
}
</style>
