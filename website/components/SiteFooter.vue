<script setup lang="ts">
/**
 * 站点 Footer（v2：深棕黑底多列，列标题 serif 字距 4px，链接字距 1px）。
 */
const columns = [
  {
    title: '购物',
    links: [
      { text: '所有商品', href: '/products' },
      { text: '按房间逛', href: '#' },
      { text: '优惠活动', href: '#' },
      { text: '新品', href: '/products?sort=newest' }
    ]
  },
  {
    title: '服务',
    links: [
      { text: 'AI 户型搭配', href: '/ai-match' },
      { text: '设计服务', href: '#' },
      { text: '送货安装', href: '#' },
      { text: '退换政策', href: '#' }
    ]
  },
  {
    title: '关于',
    links: [
      { text: '品牌故事', href: '#' },
      { text: '门店位置', href: '#' },
      { text: '对公业务', href: '#' },
      { text: '加入我们', href: '#' }
    ]
  },
  {
    title: '联系',
    links: [
      { text: '在线客服', href: '#' },
      { text: '400-888-XXXX', href: '#' },
      { text: '商务合作', href: '#' }
    ]
  }
]
</script>

<template>
  <footer>
    <div class="wrap">
      <div class="ft">
        <div>
          <div class="f-brand">rooom.vip 家居全案</div>
          <p style="line-height: 2;">为每一个家，提供买得起的<br>好设计与好品质。</p>
        </div>
        <div v-for="col in columns" :key="col.title">
          <h4>{{ col.title }}</h4>
          <a v-for="link in col.links" :key="link.text" :href="link.href">{{ link.text }}</a>
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
  margin-top: 80px;
  background: var(--footer-bg);
  color: var(--footer-text);
  font-size: 12px;
}

.ft {
  display: grid;
  grid-template-columns: 2fr 1fr 1fr 1fr 1fr;
  gap: 40px;
  padding: 60px 0 44px;
}

.ft h4 {
  color: var(--on-deep);
  font-family: var(--font-serif);
  font-size: 14px;
  margin-bottom: 18px;
  letter-spacing: 4px;
}

.ft a {
  display: block;
  padding: 5px 0;
  opacity: .85;
  letter-spacing: 1px;
}

.ft a:hover {
  opacity: 1;
  color: #fff;
}

.f-brand {
  font-family: var(--font-serif);
  font-size: 24px;
  font-weight: 700;
  color: var(--on-deep);
  letter-spacing: 3px;
  margin-bottom: 16px;
}

.copyright {
  border-top: 1px solid rgba(255, 255, 255, .08);
  padding: 20px 0;
  font-size: 11px;
  opacity: .6;
  letter-spacing: 2px;
  display: flex;
  justify-content: space-between;
}

@media (max-width: 767px) {
  .ft {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
