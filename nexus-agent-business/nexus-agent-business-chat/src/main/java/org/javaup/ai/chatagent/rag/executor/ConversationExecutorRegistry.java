package org.javaup.ai.chatagent.rag.executor;

import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;


/*ConversationExecutorRegistry：用模式找到执行器    注册表的自动装配
ConversationExecutorRegistry 来负责管理具体的执行器，这是典型的 工厂模式 + 策略模式 的组合。
扩展性：如果你想加一种新执行模式，只需要：1）在 ExecutionMode 枚举加一个值；2）写一个新的 ConversationExecutor 实现类并标注 @Component。注册表会自动发现它。
    对话执行器注册表。
 * 该注册表承担“执行模式 -> 具体执行器实现”的分发职责，
 * 让上层业务只需要关心执行模式，而不用显式编写一串 if/else 或 switch 去决定具体执行器。
 */
@Component
public class ConversationExecutorRegistry {
    /*
   执行器注册表：策略路由模式
   编排器确定了执行模式之后，ConversationExecutorRegistry 负责找到对应的执行器。这里用的是经典的策略模式 + 注册表：
     */
// ConversationExecutorRegistry.java —— 执行器注册表
    // 用 EnumMap 存储执行模式到执行器的映射，查找效率 O(1)                // 使用 EnumMap 可以让基于枚举 key 的查找更加紧凑、高效，也更符合当前场景。
    private final Map<ExecutionMode, ConversationExecutor> executorMap = new EnumMap<>(ExecutionMode.class);


    /**
     * 在 Spring 启动时收集所有执行器实现，并按其 mode 注册到内存映射表中。
     *
     * @param executors Spring 自动注入的全部执行器实现
     */
    // Spring 会自动注入所有实现了 ConversationExecutor 接口的 Bean
    public ConversationExecutorRegistry(List<ConversationExecutor> executors) {//构造方法，构造时自动发现所有执行器 Bean，按 mode 注册到 EnumMap
        for (ConversationExecutor executor : executors) {
            // 每个执行器通过自身声明的 mode 参与注册，避免注册中心硬编码了解各个实现类
            executorMap.put(executor.mode(), executor);
        }
    }

    /**
     * 根据执行模式获取对应执行器。
     *
     * @param mode 执行模式
     * @return 对应的执行器实现
     */
    public ConversationExecutor get(ExecutionMode mode) {//根据模式取执行器，找不到抛 IllegalStateException
        ConversationExecutor executor = executorMap.get(mode);
        if (executor == null) {
            // 如果模式没有对应执行器，说明配置或实现不完整，这是明确的系统状态错误。
            throw new IllegalStateException("未找到执行模式对应的执行器: " + mode);
        }
        return executor;
    }
}



/*这个注册表的工作原理其实还是很简单的：
自动注册
构造函数接收一个 List<ConversationExecutor> 参数。Spring 容器启动的时候，会自动把所有实现了 ConversationExecutor 接口的 Bean 收集起来，注入到这个列表里。然后遍历这个列表，把每个执行器按它声明的 mode() 放进 EnumMap。
所以不需要任何手动配置——只要你写了一个新的执行器类，加上 @Component 注解，实现 ConversationExecutor 接口并在 mode() 方法里返回对应的枚举值，Spring 启动时就会自动把它注册进来。
模式分发
get(ExecutionMode mode) 方法就是一个简单的 Map 查找。传入执行模式枚举值，返回对应的执行器实例。找不到就直接抛异常——因为这说明系统配置不完整，属于启动阶段就该发现的问题。

为什么用 EnumMap 而不是 HashMap？
EnumMap 的底层是一个数组，以枚举的 ordinal() 作为下标直接定位，比 HashMap 的哈希计算更快，内存占用也更小。对于 key 是枚举类型的场景来说，EnumMap 是最佳选择。
 */