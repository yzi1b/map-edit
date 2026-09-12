package cn.lyricraft.mapedit;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import java.util.Collection;
import java.util.List;

/**
 * 路径参数：读取到空白为止的全部字符（含 / 与中文），无需引号。
 * 地图画路径段的命名约定禁止空格，因此该解析不会截断合法路径。
 * 实现 Paper CustomArgumentType（自定义 ArgumentType 必须包装该接口）。
 */
public final class MapPathArgument implements CustomArgumentType<String, String> {

    private static final List<String> EXAMPLES = List.of("/dir/画", "测试", "/a/b/画1");

    private MapPathArgument() {
    }

    public static ArgumentType<String> path() {
        return new MapPathArgument();
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        while (reader.canRead() && !Character.isWhitespace(reader.peek())) {
            reader.skip();
        }
        String value = reader.getString().substring(start, reader.getCursor());
        if (value.isEmpty()) {
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument().createWithContext(reader);
        }
        return value;
    }

    /** Paper 需要：native 类型（不要求非空，由原生 string 类型兜底解析） */
    @Override
    public ArgumentType<String> getNativeType() {
        return StringArgumentType.greedyString();
    }

    @Override
    public Collection<String> getExamples() {
        return EXAMPLES;
    }
}
