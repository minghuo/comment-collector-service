package com.sysj.collector.core.circuit;

/**
 * 供应商熔断状态。
 *
 * <pre>
 * CLOSED ──(失败率/慢调用率超阈值)──> OPEN ──(冷却期结束)──> HALF_OPEN
 *    ▲                                                        │
 *    └────────────(探测全部成功)──────────────────────────────┤
 *                                                             │
 *    OPEN <──────────────(任一探测失败)───────────────────────┘
 * </pre>
 */
public enum CircuitState {

    /** 正常放行。 */
    CLOSED,

    /** 熔断中，拒绝全部请求，等待冷却期结束。 */
    OPEN,

    /** 半开：只放行少量探测请求，据其结果决定回到 CLOSED 还是重新 OPEN。 */
    HALF_OPEN
}
