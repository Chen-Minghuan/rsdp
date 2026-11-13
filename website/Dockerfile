# RSDP 用户端官网（Nuxt 3 SSR）生产镜像
# 构建产物为 Nitro Node 服务器（.output），运行时经 NUXT_PUBLIC_API_BASE 注入后端地址
# （SSR 服务端用绝对地址直连 server 容器；浏览器端走同源 /api，由外层 Nginx 转发）

FROM node:20-alpine AS deps

WORKDIR /app

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN npm config set registry https://registry.npmmirror.com \
    && npm install -g pnpm@9 \
    && pnpm config set registry https://registry.npmmirror.com \
    && pnpm install

FROM deps AS build
COPY . .
RUN pnpm build

FROM node:20-alpine

ENV TZ=Asia/Shanghai
ENV NODE_ENV=production
ENV PORT=3000

WORKDIR /app

COPY --from=build /app/.output ./.output

EXPOSE 3000

CMD ["node", ".output/server/index.mjs"]
