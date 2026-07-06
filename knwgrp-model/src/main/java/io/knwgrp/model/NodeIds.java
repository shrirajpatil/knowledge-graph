package io.knwgrp.model;

/**
 * Central place for constructing stable node ids so every extractor agrees on the same
 * identifier for the same real-world entity (critical for the resolver/linker step, which
 * must be able to find "the node for class X" without knowing who created it).
 */
public final class NodeIds {

    private NodeIds() {
    }

    public static String forModule(String moduleName) {
        return "MODULE:" + moduleName;
    }

    public static String forPackage(String packageName) {
        return "PACKAGE:" + packageName;
    }

    public static String forClass(String fullyQualifiedName) {
        return "CLASS:" + fullyQualifiedName;
    }

    public static String forMethod(String fullyQualifiedClassName, String methodSignature) {
        return "METHOD:" + fullyQualifiedClassName + "#" + methodSignature;
    }

    public static String forField(String fullyQualifiedClassName, String fieldName) {
        return "FIELD:" + fullyQualifiedClassName + "#" + fieldName;
    }

    public static String forEndpoint(String httpMethod, String path) {
        return "ENDPOINT:" + httpMethod.toUpperCase() + " " + path;
    }

    public static String forTable(String tableName) {
        return "TABLE:" + tableName.toLowerCase();
    }

    public static String forKafkaTopic(String topicName) {
        return "TOPIC:" + topicName;
    }

    public static String forQueue(String queueName) {
        return "QUEUE:" + queueName;
    }

    public static String forConfigProperty(String key) {
        return "CONFIG:" + key;
    }

    public static String forExternalService(String name) {
        return "EXTSVC:" + name;
    }

    public static String forFeignClient(String name) {
        return "FEIGN:" + name;
    }

    public static String forDatabase(String name) {
        return "DB:" + name;
    }
}
