package com.atguigu.gulimall.product.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.atguigu.gulimall.product.service.CategoryBrandRelationService;
import com.atguigu.gulimall.product.vo.Catelog2Vo;
import org.apache.commons.lang.StringUtils;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.atguigu.common.utils.PageUtils;
import com.atguigu.common.utils.Query;

import com.atguigu.gulimall.product.dao.CategoryDao;
import com.atguigu.gulimall.product.entity.CategoryEntity;
import com.atguigu.gulimall.product.service.CategoryService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;


@Service("categoryService")
public class CategoryServiceImpl extends ServiceImpl<CategoryDao, CategoryEntity> implements CategoryService {

//    @Autowired
//    CategoryDao categoryDao;

    @Autowired
    CategoryBrandRelationService categoryBrandRelationService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedissonClient redisson;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<CategoryEntity> page = this.page(
                new Query<CategoryEntity>().getPage(params),
                new QueryWrapper<CategoryEntity>()
        );

        return new PageUtils(page);
    }

    @Override
    public List<CategoryEntity> listWithTree() {
        //1、查出所有分类
        List<CategoryEntity> entities = baseMapper.selectList(null);

        //2、组装成父子的树形结构

        //2.1）、找到所有的一级分类
        List<CategoryEntity> level1Menus = entities.stream().filter(categoryEntity ->
                categoryEntity.getParentCid() == 0
        ).map((menu) -> {
            menu.setChildren(getChildrens(menu, entities));
            return menu;
        }).sorted((menu1, menu2) -> {
            return (menu1.getSort() == null ? 0 : menu1.getSort()) - (menu2.getSort() == null ? 0 : menu2.getSort());
        }).collect(Collectors.toList());


        return level1Menus;
    }

    @Override
    public void removeMenuByIds(List<Long> asList) {
        //TODO  1、检查当前删除的菜单，是否被别的地方引用

        //逻辑删除
        baseMapper.deleteBatchIds(asList);
    }

    //[2,25,225]
    @Override
    public Long[] findCatelogPath(Long catelogId) {
        List<Long> paths = new ArrayList<>();
        List<Long> parentPath = findParentPath(catelogId, paths);

        Collections.reverse(parentPath);


        return parentPath.toArray(new Long[parentPath.size()]);
    }

    /**
     * 级联更新所有关联的数据
     * @CacheEvict 失效模式
     * 1、同时进行多种缓存操作 @Caching
     * 2、指定删除某个分区下的所有数据 @CacheEvict(value = "category", allEntries = true)
     * 3、存储同一类型的数据，都可以指定成同一个分区。分区名默认就是缓存的前缀
     * @param category
     */

//    @Caching(evict = {
//            @CacheEvict(value = "category", key = "'getLeve1Categorys'"),
//            @CacheEvict(value = "category", key = "'getCatelogJson'")
//    })
    // category:key
    @CacheEvict(value = "category", allEntries = true) //失效模式
    //@CachePut // 双写模式
    @Transactional
    @Override
    public void updateCascade(CategoryEntity category) {
        this.updateById(category);
        categoryBrandRelationService.updateCategory(category.getCatId(), category.getName());

    // 同时修改缓存中的数据
    // redis.del("catalogJSON"); 等待下一次主动查询进行更新
    }

    /* 1、每一个需要缓存的数据我们都来指定要放到哪个名字的缓存。【缓存的分区（按照业务类型）】
    *  2、@Cacheable({"category"})
    *     代表当前方法的结果需要缓存，如果缓存中有，方法不用调用。
    *     如果缓存中没有，会调用方法，最后将方法的结果放入缓存。
    *  3、默认行为
    *     1）、如果缓存中有，方法不用调用。
    *     2）、key 默认自动生成，缓存的名字：SimpleKey[]（自动生成的 key 值）
    *     3）、缓存的 value 的值。默认使用 JDK 序列化机制，将序列化的数据存到 redis
    *     4）、默认ttl时间 -1；
    *    自定义的操作：
    *     1）、指定生成的缓存使用的 key：key属性指定，接受一个spEL
    *     2）、指定缓存的数据的存储时间：配置文件中修改 ttl
    *     3）、将数据保存为 json 格式
    * 4、Spring-Cache 的不足
    *     1）、读模式：
    *         缓存穿透：查询一个 null 数据。解决：缓存空数据：cache-null-values=true
    *         缓存击穿：大量并发进来同时查询一个正好过期的数据。解决方案：加锁？ -> 默认是无加锁的 sync = true（加锁，解决击穿）
    *         缓存雪崩：大量的 key 同时过期。解决：加随机时间。加上过期时间。spring.cache.redis.time-to-live=3600000
    *     2)、写模式：
    *         1）、读写加锁。
    *         2）、引用 canal，感知到 MySQL 的更新去更新数据库
    *         3）、读多写多，直接去数据库查询就行
    *    总结：
    *     常规数据（读多写少，即时性，一致性要求不高的数据）；完全可以使用 spring-cache；写模式（只要缓存的数据有过期时间就足q够了）
    *     特殊数据：特殊设计
    *
    * 原理：
    *    CacheManager（RedisCacheManager） -> Cache（RedisCache） -> Cache负责缓存的读写
    *
    */
    @Cacheable(value = {"category"},key = "#root.method.name", sync = true)
    @Override
    public List<CategoryEntity> getLeve1Categorys() {
        long start = System.currentTimeMillis();
        List<CategoryEntity> categoryEntities = baseMapper.selectList(new QueryWrapper<CategoryEntity>().eq("parent_cid", 0));
        System.out.println("消耗时间：" + (System.currentTimeMillis() - start));
        return categoryEntities;
    }

    @Override
    @Cacheable(value = "category", key = "#root.methodName")
    public Map<String, List<Catelog2Vo>> getCatelogJson(){
        System.out.println("查询了数据库....");
        List<CategoryEntity> selectList = baseMapper.selectList(null);
        List<CategoryEntity> leve1Categorys = getParent_cid(selectList, 0L);
        // 封装数据
        Map<String, List<Catelog2Vo>> parent_cid = leve1Categorys.stream().collect(Collectors.toMap(k -> k.getCatId().toString(), v -> {
            // 1、每一个的一级分类，查到这个一级分类的的二级分类
            List<CategoryEntity> categoryEntities = getParent_cid(selectList, v.getCatId());
            // 2、封装上面的结果
            List<Catelog2Vo> catelog2Vos = null;
            if (categoryEntities != null) {
                catelog2Vos = categoryEntities.stream().map(l2 -> {
                    Catelog2Vo catelog2Vo = new Catelog2Vo(v.getCatId().toString(), null,
                            l2.getCatId().toString(), l2.getName());
                    // 1、找当前二级分类的三级分类封装成 vo
                    List<CategoryEntity> level3Catelog = getParent_cid(selectList, l2.getCatId());
                    if (level3Catelog != null) {
                        List<Catelog2Vo.Catelog3Vo> collect = level3Catelog.stream().map(l3 -> {
                            // 2、封装成指定格式
                            Catelog2Vo.Catelog3Vo catelog3Vo = new Catelog2Vo.Catelog3Vo(l2.getCatId().toString(), l3.getCatId().toString(), l3.getName());
                            return catelog3Vo;
                        }).collect(Collectors.toList());
                        catelog2Vo.setCatalog3List(collect);
                    }
                    return catelog2Vo;
                }).collect(Collectors.toList());
            }
            return catelog2Vos;
        }));
        return parent_cid;
    }

    // TODO 产生堆外内存溢出，OutOfDirectMemoryError
    // 1）、springboot2.0以后默认使用 letture 作为操作 redis 的客户端。它使用 netty 进行网络通信。
    // 2）、letture的 bug 导致堆外内存溢出 -Xmx 300m，netty 如果没有指定堆外内存，默认使用 -Xmx 300m
    //    可以通过 -Dio.netty.maxDrectMemory 进行设置
    // 解决方案：不能使用  -Dio.netty.maxDrectMemory 只去调大堆外内存
    // 1）、升级 letture 客户端。   2)、切换使用 jedis
    //   redisTemplate：
    //   letture、jedis 操作 redis 的底层客户端。Spring 再次封装 redisTemplate。
    //@Override
    public Map<String, List<Catelog2Vo>> getCatelogJson2() {
        // 给缓存中放 json 字符串，拿出的 json 字符串，还要逆转为能用的对象类型，【序列号和反序列化】

        /**
         * 1、空结果缓存：解决缓存穿透问题
         * 2、设置过期时间（加随机值）：解决缓存雪崩问题
         * 3、加锁：解决缓存击穿问题
         */

        // 1、加入缓存逻辑，缓存中所有的数据都是json字符串
        // JSON 跨语言，跨平台的兼容。
        String catelogJSON = redisTemplate.opsForValue().get("catelogJSON");
        if (StringUtils.isEmpty(catelogJSON)) {
            // 2、缓存中没有，查询数据库
            System.out.println("缓存不命中....将要查询数据库");
            Map<String, List<Catelog2Vo>> catelogJsonFromDb = getCatelogJsonFromDbWithRedisLock();

            return catelogJsonFromDb;
        }
        System.out.println("缓存命中....直接返回");
        // 转为我们指定的对象
        Map<String, List<Catelog2Vo>> result = JSON.parseObject(catelogJSON, new TypeReference<Map<String, List<Catelog2Vo>>>() {
        });
        return result;
    }

    /**
     * 缓存里面的数据如何与数据库保存一致
     * 缓存数据一致性
     * 1）、双写模式
     * 2）、失效模式
     * @return
     */
    public Map<String, List<Catelog2Vo>> getCatelogJsonFromDbWithRedissonLock() {
        //1、锁的名字。锁的粒度，越细越快。
        // 锁的粒度，具体缓存的时某个数据，11-号商品：product-11-lock product-12-lock
        RLock lock = redisson.getLock("CatalogJson-lock");
        lock.lock();
        Map<String, List<Catelog2Vo>> dataFromDb;
        try {
            dataFromDb = getDataFromDb();
        } finally {
            lock.unlock();
        }
        return dataFromDb;

    }

    public Map<String, List<Catelog2Vo>> getCatelogJsonFromDbWithRedisLock() {
        // 占分布式锁。去 redis 占坑
        String uuid = UUID.randomUUID().toString();
        Boolean lock = redisTemplate.opsForValue().setIfAbsent("lock", uuid, 300, TimeUnit.SECONDS);
        if (lock) {
            System.out.println("获取分布式锁成功....");
            // 加锁成功... 执行业务
            // 2、设置过期时间，必须和加锁是同步的，原子的
            //redisTemplate.expire("lock",30, TimeUnit.SECONDS);
            Map<String, List<Catelog2Vo>> dataFromDb;
            try {
                dataFromDb = getDataFromDb();
            } finally {
                String script = "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end";
                Long lock1 = redisTemplate.execute(new DefaultRedisScript<>(script, Long.class)
                        , Arrays.asList("lock"), uuid);
            }

            //redisTemplate.delete("lock"); // 删除锁

            // 获取值对比，对比成功删除 = 原子操作 lua 脚本解锁
            /*String lockValue = redisTemplate.opsForValue().get("lock");
            if (uuid.equals(lockValue)){
                // 删除自己的锁
                redisTemplate.delete("lock"); // 删除锁
            }*/
            return dataFromDb;
        } else {
            // 加锁失败...重试。synchrionized()
            System.out.println("获取分布式锁失败....等待重试");
            // 休眠 100ms 重试
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return getCatelogJsonFromDbWithRedisLock(); // 自旋的方式
        }
    }

    private Map<String, List<Catelog2Vo>> getDataFromDb()  {
        String catelogJSON = redisTemplate.opsForValue().get("catelogJSON");
        if (!StringUtils.isEmpty(catelogJSON)) {
            // 如果缓存不为 null 直接返回
            Map<String, List<Catelog2Vo>> result = JSON.parseObject(catelogJSON, new TypeReference<Map<String, List<Catelog2Vo>>>() {
            });
            return result;
        }
        System.out.println("查询了数据库....");
        List<CategoryEntity> selectList = baseMapper.selectList(null);
        List<CategoryEntity> leve1Categorys = getParent_cid(selectList, 0L);
        // 封装数据
        Map<String, List<Catelog2Vo>> parent_cid = leve1Categorys.stream().collect(Collectors.toMap(k -> k.getCatId().toString(), v -> {
            // 1、每一个的一级分类，查到这个一级分类的的二级分类
            List<CategoryEntity> categoryEntities = getParent_cid(selectList, v.getCatId());
            // 2、封装上面的结果
            List<Catelog2Vo> catelog2Vos = null;
            if (categoryEntities != null) {
                catelog2Vos = categoryEntities.stream().map(l2 -> {
                    Catelog2Vo catelog2Vo = new Catelog2Vo(v.getCatId().toString(), null,
                            l2.getCatId().toString(), l2.getName());
                    // 1、找当前二级分类的三级分类封装成 vo
                    List<CategoryEntity> level3Catelog = getParent_cid(selectList, l2.getCatId());
                    if (level3Catelog != null) {
                        List<Catelog2Vo.Catelog3Vo> collect = level3Catelog.stream().map(l3 -> {
                            // 2、封装成指定格式
                            Catelog2Vo.Catelog3Vo catelog3Vo = new Catelog2Vo.Catelog3Vo(l2.getCatId().toString(), l3.getCatId().toString(), l3.getName());
                            return catelog3Vo;
                        }).collect(Collectors.toList());
                        catelog2Vo.setCatalog3List(collect);
                    }
                    return catelog2Vo;
                }).collect(Collectors.toList());
            }
            return catelog2Vos;
        }));
        // 3、查到的数据放入缓将对象转为json放入缓存中
        String s = JSON.toJSONString(parent_cid);
        redisTemplate.opsForValue().set("catelogJSON", s, 1, TimeUnit.DAYS);
        return parent_cid;
    }

    // 从数据库查询并封装分类数据
    public Map<String, List<Catelog2Vo>> getCatelogJsonFromDbWithLocalLock() {
        // 只要是同一把锁，就能锁住需要这个锁的所有线程
        // 1、synchronized(this)：SpringBoot 所有的组件在容器中都是单例的。
        // TODO 本地锁：synchronized，JUC（Lock）,在分布式情况下，想要锁住所有，必须使用分布式锁
        synchronized (this) {
            // 得到锁之后，我们应该再去缓存中确定一次，认没没有才需要继续查询
            return getDataFromDb();
        }
    }

    private List<CategoryEntity> getParent_cid(List<CategoryEntity> selectList, Long parent_cid) {
        List<CategoryEntity> collect = selectList.stream().filter(item ->
                item.getParentCid() == parent_cid
        ).collect(Collectors.toList());
        return collect;

        //return baseMapper.selectList(new QueryWrapper<CategoryEntity>().eq("parent_cid", v.getCatId()));
    }

    //225,25,2
    private List<Long> findParentPath(Long catelogId, List<Long> paths) {
        //1、收集当前节点id
        paths.add(catelogId);
        CategoryEntity byId = this.getById(catelogId);
        if (byId.getParentCid() != 0) {
            findParentPath(byId.getParentCid(), paths);
        }
        return paths;

    }


    //递归查找所有菜单的子菜单
    private List<CategoryEntity> getChildrens(CategoryEntity root, List<CategoryEntity> all) {

        List<CategoryEntity> children = all.stream().filter(categoryEntity -> {
            //return categoryEntity.getParentCid() == root.getCatId();
            return root.getCatId().equals(categoryEntity.getParentCid());
        }).map(categoryEntity -> {
            //1、找到子菜单
            categoryEntity.setChildren(getChildrens(categoryEntity, all));
            return categoryEntity;
        }).sorted((menu1, menu2) -> {
            //2、菜单的排序
            return (menu1.getSort() == null ? 0 : menu1.getSort()) - (menu2.getSort() == null ? 0 : menu2.getSort());
        }).collect(Collectors.toList());

        return children;
    }


}