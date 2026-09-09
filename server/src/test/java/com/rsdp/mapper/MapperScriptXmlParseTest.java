package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Mapper 注解 SQL 的 XML 良构性测试。
 *
 * <p>MyBatis 在 SqlSessionFactory 初始化时才真正解析 {@code @Select("<script>...")} 中的动态 SQL，
 * 而项目单测均为 Mock、无 SpringBootTest，导致 XML 转义缺陷（如裸 {@code <}、属性值含同种引号）
 * 会漏到应用启动期才暴露。本测试用 {@link MybatisConfiguration#addMapper} 提前触发全部
 * Mapper 的语句解析，将启动期故障左移到单元测试，无需数据库等外部依赖。</p>
 */
class MapperScriptXmlParseTest {

    @Test
    void allMapperAnnotationScriptsAreWellFormedXml() throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        List<Class<?>> mappers = scanMapperInterfaces();
        org.assertj.core.api.Assertions.assertThat(mappers)
            .as("应至少扫描到 com.rsdp.mapper 下的一个 Mapper").isNotEmpty();
        for (Class<?> mapper : mappers) {
            configuration.addMapper(mapper);
        }
    }

    private List<Class<?>> scanMapperInterfaces() throws Exception {
        String packagePath = "com/rsdp/mapper";
        // test-classes 与 classes 都在 classpath 且同名目录，需聚合所有资源URL
        Enumeration<URL> dirs = Thread.currentThread().getContextClassLoader().getResources(packagePath);
        Set<String> classNames = new LinkedHashSet<>();
        while (dirs.hasMoreElements()) {
            File[] files = new File(dirs.nextElement().toURI()).listFiles(
                (d, name) -> name.endsWith(".class") && !name.contains("$"));
            if (files == null) {
                continue;
            }
            for (File file : files) {
                classNames.add("com.rsdp.mapper." + file.getName().replace(".class", ""));
            }
        }
        List<Class<?>> mappers = new ArrayList<>();
        for (String className : classNames) {
            Class<?> clazz = Class.forName(className);
            if (clazz.isInterface()) {
                mappers.add(clazz);
            }
        }
        return mappers;
    }
}
