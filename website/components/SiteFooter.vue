<script setup lang="ts">
/**
 * 站点 Footer（深棕黑底多列：品牌 2fr + 购物/服务/关于/联系各 1fr + 版权行）。
 */
const columns = [
  {
    title: '购物',
    links: ['所有商品', '按房间逛', '优惠活动', '新品']
  },
  {
    title: '服务',
    links: ['AI 户型搭配', '设计服务', '送货安装', '退换政策']
  },
  {
    title: '关于',
    links: ['品牌故事', '门店位置', '对公业务', '加入我们']
  },
  {
    title: '联系',
    links: ['在线客服', '400-888-XXXX', '商务合作']
  }
]
</script>

<template>
  <footer>
    <div class="wrap">
      <div class="ft">
        <div>
          <div class="f-brand">rooom.vip 家居全案</div>
          <p style="line-height: 1.9;">为每一个家，提供买得起的<br>好设计与好品质。</p>
        </div>
        <div v-for="col in columns" :key="col.title">
          <h4>{{ col.title }}</h4>
          <a v-for="link in col.links" :key="link" href="#">{{ link }}</a>
        </div>
      </div>
      <div class="copyright">
        <span>© 2026 rooom.vip 家居全案 · 粤ICP备XXXXXXXX号</span>
        <span>隐私政策 · Cookie 设置</span>
      </div>
    </div>
  </footer>
</template>

<style scoped>
footer {
  margin-top: 64px;
  background: #241a12;
  color: #b9a892;
  font-size: 13px;
}

.ft {
  display: grid;
  grid-template-columns: 2fr 1fr 1fr 1fr 1fr;
  gap: 32px;
  padding: 52px 0 36px;
}

.ft h4 {
  color: var(--suppl);
  font-family: var(--font-serif);
  font-size: 15px;
  margin-bottom: 16px;
  letter-spacing: 3px;
}

.ft a {
  display: block;
  padding: 4px 0;
  opacity: .9;
}

.ft a:hover {
  opacity: 1;
  color: #fff;
  text-decoration: underline;
}

.f-brand {
  font-family: var(--font-serif);
  font-size: 23px;
  font-weight: 700;
  color: var(--suppl);
  letter-spacing: 2px;
  margin-bottom: 14px;
}

.copyright {
  border-top: 1px solid rgba(255, 255, 255, .1);
  padding: 18px 0;
  font-size: 12px;
  opacity: .65;
  letter-spacing: 1px;
  display: flex;
  justify-content: space-between;
}

@media (max-width: 767px) {
  .ft {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
