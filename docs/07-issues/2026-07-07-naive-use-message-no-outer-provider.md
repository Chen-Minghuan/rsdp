# 问题记录：[naive/use-message]: No outer <n-message-provider /> founded

## 发生时间

2026-07-07

## 现象

前端页面报错（控制台）：

```
Uncaught (in promise) Error: [naive/use-message]: No outer <n-message-provider /> founded.
    at setup (ProductListView.vue:29:17)
```

刷新后反复出现，影响使用 `useMessage()` 的页面。

## 触发页面

- `ProductListView.vue`（产品库列表页）
- 任何在 `<script setup>` 中调用 `useMessage()`、`useDialog()`、`useNotification()` 的组件

## 根因

Naive UI 的 `useMessage()`、`useDialog()`、`useNotification()` 等组合式 API **必须在对应的 Provider 组件内部调用**。

项目根组件 `web/src/App.vue` 只包裹了 `<n-dialog-provider>`，但缺少 `<n-message-provider>` 和 `<n-notification-provider>`。当 `ProductListView.vue` 调用 `useMessage()` 时，找不到上层 Provider，于是抛出错误。

## 修复方法

在 `App.vue` 中，把 `router-view` 同时包裹在 `n-message-provider`、`n-notification-provider`、`n-dialog-provider` 内部：

```vue
<n-layout content-style="overflow-y: auto;">
  <n-message-provider>
    <n-notification-provider>
      <n-dialog-provider>
        <router-view />
      </n-dialog-provider>
    </n-notification-provider>
  </n-message-provider>
</n-layout>
```

并补充对应组件的 import：

```ts
import {
  NConfigProvider, zhCN, dateZhCN, NLayout, NLayoutHeader,
  NButton, NSpace, NDialogProvider, NDropdown,
  NMessageProvider, NNotificationProvider
} from 'naive-ui'
```

## 验证

- `pnpm type-check` 通过
- `pnpm lint` 通过
- 刷新浏览器后，`useMessage()` 不再报错

## 排查 checklist（以后遇到类似报错直接对照）

1. 报错中是否提到 `use-message` / `use-dialog` / `use-notification` / `use-loading-bar` / `use-modal`？
2. `App.vue` 是否已挂载对应的 `<n-xxx-provider>`？
3. Provider 是否包裹了使用 hook 的组件所在的 `router-view`？
4. 是否在 Provider 外部调用了 hook（例如在 Pinia store 或路由守卫中直接调用）？

## 参考链接

- Naive UI 官方文档（Message 前置条件）：https://www.naiveui.com/zh-CN/os-theme/components/message
