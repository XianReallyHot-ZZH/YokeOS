package com.yokeos.web.controller.dto;

import java.util.List;

/**
 * 工作区目录树节点（第 30 节，data-model）：tree 端点返回结构。path 为相对 workspace root 的路径（file 端点的入参形态）。
 *
 * @param name 文件/目录名
 * @param type {@code dir} 或 {@code file}
 */
public record FileNode(String name, String path, String type, List<FileNode> children) {

  /** 目录节点构造（children 防御性拷贝）。 */
  public FileNode {
    children = children == null ? List.of() : List.copyOf(children);
  }

  /** 目录节点工厂。 */
  public static FileNode dir(String name, String path, List<FileNode> children) {
    return new FileNode(name, path, "dir", children);
  }

  /** 文件节点工厂（无子节点）。 */
  public static FileNode file(String name, String path) {
    return new FileNode(name, path, "file", null);
  }
}
