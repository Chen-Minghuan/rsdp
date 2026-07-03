package com.rsdp.security.datascope;

/**
 * 数据范围枚举。
 */
public enum DataScope {

    /** 全部数据 */
    ALL,

    /** 指定工厂列表 */
    FACTORY_LIST,

    /** 自己创建的数据 */
    SELF_CREATED,

    /** 仅公开数据 */
    PUBLIC_ONLY
}
