# RSDP 管理端前端（多阶段：依赖 → 测试 → 构建 → Nginx 运行）
# test 阶段执行 type-check + lint + vitest，与 make test 前端部分对齐；
# Jenkins「Frontend tests」阶段使用 --target test 构建即完成前端测试闸。

FROM node:20-alpine AS deps

WORKDIR /app

COPY package.json pnpm-lock.yaml* ./
RUN npm config set registry https://registry.npmmirror.com \
    && npm install -g pnpm@9 \
    && pnpm config set registry https://registry.npmmirror.com \
    && pnpm install

FROM deps AS test
COPY . .
RUN pnpm type-check && pnpm lint && pnpm test

FROM test AS builder
RUN pnpm build

FROM nginx:1.27-alpine

COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
