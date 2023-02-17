package com.djdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.djdp.dto.Result;
import com.djdp.entity.VoucherOrder;
import com.djdp.mapper.VoucherOrderMapper;
import com.djdp.service.ISeckillVoucherService;
import com.djdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.djdp.utils.RedisIdWorker;
import com.djdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    
    @Resource
    private RedissonClient redissonClient;


    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    //在当前类初始化完毕后开始执行
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * 获取消息队列的订单信息
     */
    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";
        @Override
        public void run() {


            while (true){
                try {
                    //1.获取订单信息   XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            //面向对象的思想
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        continue;
                    }
                    //解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object,Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value,new VoucherOrder(),true);

                    //如果获取成功，可以下单
                    createVoucherOrder(voucherOrder);
                    //ACK确认  SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                    

                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    handlePendingList();
                }
            }
        }
        private void handlePendingList() {

            while (true) {
                try {
                    // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为pengding-list，说明没有异常消息，结束循环
                        break;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    createVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }

    }




    /*
    private BlockingQueue<VoucherOrder> orderTasks=new ArrayBlockingQueue<>(1024*1024);
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {

            while (true){
                try {
                    //1.获取订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.创建订单
                    handleVoucherOrder(voucherOrder);

                } catch (InterruptedException e) {
                    log.error("处理订单异常",e);

                }
            }
        }

    }
    */
    private IVoucherOrderService proxy;
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //获取用户id 因为是线程（异步的） 因此不能使用UserHolder类来获取了
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        RLock lock = redissonClient.getLock("lock:order" + userId);
        //获取锁
        boolean isLock = lock.tryLock();
        //判断是否获取锁成功
        if (!isLock){
            log.error("不允许重复下单");
            return ;
        }

        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }

    }


    @Override
    public Result seckillVoucher(Long voucherId) {

        //获取用户
        Long userId = UserHolder.getUser().getId();
        //获取订单
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本 ::变化：传参加了一个orderId 因此我们要提前船舰订单
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),String.valueOf(orderId)//不同类型的字符串转换
        );


        int r = result.intValue();
        // 2.判断结果是否为0
        if (r!=0){
            // 2.1.不为0则返回异常信息
            return Result.fail(result==1?"库存不足":"不能重复下单");
        }



        //获取代理对象（事务）
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 3.返回订单队列
        return Result.ok(orderId);
    }

    /*

    @Override
    public Result seckillVoucher(Long voucherId) {

        //获取用户
        Long userId = UserHolder.getUser().getId();
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );

        assert result != null;
        int i = result.intValue();
        // 2.判断结果是否为0
        if (result!=0){
            // 2.1.不为0则返回异常信息
            return Result.fail(result==1?"库存不足":"不能重复下单");
        }
        // 2.2.为0则将信息保存到阻塞队列
        long orderId = redisIdWorker.nextId("order");
        //TODO 保存到阻塞队列

        //3.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 3.1.订单id
        voucherOrder.setId(orderId);

        // 3.2.用户id
        voucherOrder.setUserId(userId);

        // 3.3.代金券id
        voucherOrder.setVoucherId(voucherId);

        orderTasks.add(voucherOrder);


        //获取代理对象（事务）
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 3.返回订单队列
        return Result.ok(orderId);
    }

     */

    /**
     * 不在方法上加锁 如果在方法上加锁的话，所有的用户进来的时候都会被一把锁所限制住 并且无法通过特定的id所著用户
     * Transactional注解里面的方法的锁依旧会导致数据偷跑的现象发生
     * 原因是，你数据更新的锁提交的时候，事务还没有提交，因此其他线程可以获取锁并开始查询，从而导致了数据偷跑
     * 因此呢，我们要做到锁后释放才能预防，因此锁应该在方法的外层使用用户id的方法去加锁，并且需要使用特殊的方法
     * 我们要做到事务完了之后才能释放锁
     * 并且此时如果在createVoucherOrder方法上加Transactional注解的话是不生效的
     * 因为事务是对代理对象生效而不是对自己本身生效,因此需要创建一个对象
     */
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //5.一人一单
        Long userId= voucherOrder.getUserId();
        //5.1 查询订单
        long count = query().eq("user_id",userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //5.2 判断该用户是否已经抢购过了优惠券（一人一单）
        if (count > 0){
            log.error("用户已经购买过一次");
            return ;
        }

        //5.3扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock= stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock",0)
                .update();
        if (!success) {
            //扣减库存
            log.error("库存不足");
            return ;
        }
        save(voucherOrder);

       /* //6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 6.1.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);

        // 6.2.用户id
        voucherOrder.setUserId(userId);


        // 6.3.代金券id
        voucherOrder.setVoucherId(voucherId);

*/



    }


    /*
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始！");
        }
        // 3.判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束！");
        }
        // 4.判断库存是否充足
        if (voucher.getStock()<1){
            //库存不足
            return Result.fail("库存不足");
        }

        Long userId= UserHolder.getUser().getId();
        //创建锁对象
        //SimpleRedisLock lock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order" + userId);
        //获取锁
        boolean isLock = lock.tryLock();
        //判断是否获取锁成功
        if (!isLock){
            return Result.fail("一人只能定制一个单票");
        }

        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }
//        //首先呢，统一用户锁过来的id是new出来的 并且userId.toString的底层也是new了一个对象，因此无法实现对同一用户加锁，intern方法类似于去
//        //字符串常量池里面去寻找真正的规范化表示
//        synchronized (userId.toString().intern()) {
//            //获取事务代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }
    }
     */
}
