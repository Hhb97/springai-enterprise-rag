package com.example.ragassitant.domain.service;

import com.example.ragassitant.domain.model.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HeuristicRouterTest {

    private final HeuristicRouter router = new HeuristicRouter();

    // ==================== 话题切换检测 ====================

    @Test
    @DisplayName("知识库问题不应被判定为「还在聊工具话题」")
    void knowledgeQuestionIsNotSameToolTopic() {
        // 线上实例：上一轮「mv-005的呢」查能耗，这一轮问「如何创建用户呢？」
        // 若判定为同话题，就会拼接历史继承 mv-005，把知识库问题劫持成能耗查询
        assertFalse(router.isLikelySameToolTopic("如何创建用户呢？", "mv-005的呢"),
                "「如何创建用户呢？」被误判为设备/能耗话题");
    }

    @Test
    @DisplayName("其他知识库类问题同样不该被误判")
    void otherKnowledgeQuestionsAreNotToolTopic() {
        String last = "mv-005的呢";
        assertFalse(router.isLikelySameToolTopic("如何重置密码呢？", last));
        assertFalse(router.isLikelySameToolTopic("角色权限怎么配置", last));
        assertFalse(router.isLikelySameToolTopic("批量导入的模板在哪", last));
    }

    @Test
    @DisplayName("真正的能耗追问仍应判定为同话题")
    void genuineFollowupIsStillSameToolTopic() {
        assertTrue(router.isLikelySameToolTopic("那近7天呢", "mv-005近半年能耗"));
        assertTrue(router.isLikelySameToolTopic("按月聚合看看", "mv-005近半年能耗"));
        assertTrue(router.isLikelySameToolTopic("这台设备的详情", "mv-005近半年能耗"));
    }

    @Test
    @DisplayName("闲聊/身份问题判定为换话题")
    void smallTalkIsTopicSwitch() {
        String last = "mv-005近半年能耗";
        assertFalse(router.isLikelySameToolTopic("你好", last));
        assertFalse(router.isLikelySameToolTopic("你是什么大模型", last));
    }

    // ==================== 闲聊短路（跳过改写/路由/检索的唯一依据）====================

    @Test
    @DisplayName("问候、致谢、AI 身份类问题识别为闲聊")
    void detectsSmallTalk() {
        // 线上事故：用户只说了句「你好」，Agent 链路无条件检索知识库又没有相似度下限，
        // 电能质量 FAQ 被召回并注入提示词，模型于是回「我已经阅读了知识库中的内容…」并总结起 FAQ。
        assertTrue(HeuristicRouter.isSmallTalk("你好"));
        assertTrue(HeuristicRouter.isSmallTalk("你好！"));
        assertTrue(HeuristicRouter.isSmallTalk("在吗"));
        assertTrue(HeuristicRouter.isSmallTalk("谢谢"));
        assertTrue(HeuristicRouter.isSmallTalk("你是什么大模型"));
        assertTrue(HeuristicRouter.isSmallTalk("你是谁开发的"));
    }

    @Test
    @DisplayName("知识库问题绝不能被当成闲聊短路掉")
    void knowledgeQuestionIsNotSmallTalk() {
        // 【必须和「换话题」分开的原因】这些问题确实是在换话题，但它们是标准的知识库问答，
        // 一旦被闲聊短路命中就会彻底拿不到检索结果。
        assertFalse(HeuristicRouter.isSmallTalk("解释下什么是电能质量"));
        assertFalse(HeuristicRouter.isSmallTalk("什么叫电压不平衡度"));
        assertFalse(HeuristicRouter.isSmallTalk("如何创建用户"));
        assertFalse(HeuristicRouter.isSmallTalk("谐波失真阈值是多少"));
    }

    @Test
    @DisplayName("问候后面跟着真实业务请求的，不算闲聊")
    void greetingWithRealRequestIsNotSmallTalk() {
        assertFalse(HeuristicRouter.isSmallTalk("你好，帮我查下 mv-003 近30天能耗"));
        assertFalse(HeuristicRouter.isSmallTalk("你好，列出所有设备"));
    }

    @Test
    @DisplayName("空输入不算闲聊")
    void blankIsNotSmallTalk() {
        assertFalse(HeuristicRouter.isSmallTalk(null));
        assertFalse(HeuristicRouter.isSmallTalk("   "));
    }

    // ==================== 路由本身 ====================

    @Test
    @DisplayName("带 SN 的追问命中近 N 天能耗")
    void snFollowupRoutesToEnergy() {
        ToolCall call = router.route("mv-005的呢");
        assertNotNull(call);
        assertEquals("query_energy_by_days", call.name);
        assertEquals("mv-005", call.arguments.get("sn"));
    }

    @Test
    @DisplayName("设备清单类问题命中 list_devices")
    void deviceListRoutes() {
        assertEquals("list_devices", router.route("列出所有设备").name);
    }

    @Test
    @DisplayName("纯知识库问题不命中任何工具")
    void knowledgeQuestionRoutesToNothing() {
        assertNull(router.route("如何创建用户呢？"));
        assertNull(router.route("角色权限怎么配置"));
    }
}
