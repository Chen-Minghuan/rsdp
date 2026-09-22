# rsdp-cad-parser Dockerfile（多阶段构建）
# CAD 户型解析微服务：ACadSharp 读 DWG/DXF + NetTopologySuite 几何运算，仅内网服务（不暴露宿主端口）

# ---- 构建阶段 ----
FROM mcr.microsoft.com/dotnet/sdk:7.0 AS build
WORKDIR /src

# 先拷 csproj 还原依赖（利用 Docker 层缓存）
COPY rsdp-cad-parser.csproj ./
RUN dotnet restore

# 拷贝源码并发布（.dockerignore 已排除 bin/obj/tests）
COPY . .
RUN dotnet publish -c Release -o /app --no-restore

# ---- 运行阶段 ----
FROM mcr.microsoft.com/dotnet/aspnet:7.0 AS runtime
WORKDIR /app
COPY --from=build /app .

# 仅内网监听；端口可用 CAD_PARSER_PORT 覆盖
ENV CAD_PARSER_PORT=8090
EXPOSE 8090

ENTRYPOINT ["dotnet", "rsdp-cad-parser.dll"]
