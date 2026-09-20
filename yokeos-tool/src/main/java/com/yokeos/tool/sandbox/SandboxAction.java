package com.yokeos.tool.sandbox;

/**
 * 一次待校验的动作（宪法 6、specs/008 D1）：类型 + 目标。target 是纯字符串——具体是路径、命令还是 URL 由 {@link ActionType}
 * 决定，实现类自己解释；接口层不出现任何一档实现特有的词。
 */
public record SandboxAction(ActionType type, String target) {}
