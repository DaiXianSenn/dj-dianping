package com.djdp.utils;

/**
 * Author: JhonDai
 * Date: 2023/02/07/15:03
 * Version: 1.0
 * Description:分布式锁的工具类，主要是解决不同jvm下（分布式系统）中无法在一个宏观的角度上枷锁从而导致的信息安全问题
 */
public interface ILock {
    /**
     * 尝试获取锁
     * @param timeoutSec 锁持有的超时时间，过期后释放
     * @return true表示获取锁成功，false代表获取锁失败
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unLock();
}
