package com.java110.center.smo.impl;

import com.java110.center.dao.ICenterServiceDAO;
import com.java110.center.smo.ICenterServiceSMO;
import com.java110.common.cache.AppRouteCache;
import com.java110.common.cache.MappingCache;
import com.java110.common.constant.MappingConstant;
import com.java110.common.constant.ResponseConstant;
import com.java110.common.exception.*;
import com.java110.common.factory.DataFlowFactory;
import com.java110.common.kafka.KafkaFactory;
import com.java110.common.log.LoggerEngine;
import com.java110.common.util.DateUtil;
import com.java110.common.util.ResponseTemplateUtil;
import com.java110.common.util.StringUtil;
import com.java110.entity.center.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 中心服务处理类
 * Created by wuxw on 2018/4/13.
 */
@Service("centerServiceSMOImpl")
@Transactional
public class CenterServiceSMOImpl implements ICenterServiceSMO {

    @Autowired
    ICenterServiceDAO centerServiceDaoImpl;

    @Override
    public String service(String reqJson, Map<String, String> headers) throws SMOException{

        DataFlow dataFlow = null;

        try {
            //1.0 创建数据流
            dataFlow = DataFlowFactory.newInstance().builder(reqJson, headers);
            //2.0 加载配置信息
            initConfigData(dataFlow);
            //3.0 校验 APPID是否有权限操作serviceCode
            judgeAuthority(dataFlow);
            //4.0 调用规则校验
            ruleValidate(dataFlow);
            //5.0 保存订单和业务项 c_orders c_order_attrs c_business c_business_attrs
            saveOrdersAndBusiness(dataFlow);
            //6.0 调用下游系统
            invokeBusinessSystem(dataFlow);

        } catch (BusinessException e) {
            try {
                //7.0 作废订单和业务项
                invalidOrderAndBusiness(dataFlow);
                //8.0 广播作废业务系统订单信息
                //想法:这里可以直接不广播，只有在业务返回时才广播,
                // 疑问：在这里部分消息发出去了，如果在receiveBusinessSystemNotifyMessage这个方法中依然没有收到，我们认为是下游系统也是失败了不用处理，
                //目前看逻辑也是对的
                //invalidBusinessSystem(dataFlow);
            } catch (Exception e1) {
                LoggerEngine.error("作废订单失败", e);
                //9.0 将订单状态改为失败，人工处理。
                updateOrderAndBusinessError(dataFlow);
            } finally {
                return ResponseTemplateUtil.createOrderResponseJson(dataFlow.getTransactionId(),
                        ResponseConstant.NO_NEED_SIGN, e.getResult().getCode(), e.getMessage());
            }

        } catch (OrdersException e) {
            return ResponseTemplateUtil.createOrderResponseJson(dataFlow.getTransactionId(),
                    ResponseConstant.NO_NEED_SIGN, e.getResult().getCode(), e.getMessage());
        } catch (RuleException e) {
            return ResponseTemplateUtil.createOrderResponseJson(dataFlow.getTransactionId(),
                    ResponseConstant.NO_NEED_SIGN, e.getResult().getCode(), e.getMessage());
        } catch (NoAuthorityException e) {
            return ResponseTemplateUtil.createOrderResponseJson(dataFlow.getTransactionId(),
                    ResponseConstant.NO_NEED_SIGN, e.getResult().getCode(), e.getMessage());
        } catch (Exception e) {
            return ResponseTemplateUtil.createOrderResponseJson(dataFlow == null
                            ? ResponseConstant.NO_TRANSACTION_ID
                            : dataFlow.getTransactionId(),
                    ResponseConstant.NO_NEED_SIGN, ResponseConstant.RESULT_CODE_INNER_ERROR, "内部异常了：" + e.getMessage() + e.getLocalizedMessage());
        } finally {
            //这里记录日志
            Date endDate = DateUtil.getCurrentDate();
            if (dataFlow == null) { //说明异常了,不能记录耗时

                return null;
            }
            dataFlow.setEndDate(endDate);

            //添加耗时
            DataFlowFactory.addCostTime(dataFlow, "service", "业务处理总耗时", dataFlow.getStartDate(), dataFlow.getEndDate());

            //这里保存耗时，以及日志

            return ResponseTemplateUtil.createCommonResponseJson(dataFlow);
        }

    }

    /**
     * 2.0初始化配置信息
     *
     * @param dataFlow
     */
    private void initConfigData(DataFlow dataFlow) {
        Date startDate = DateUtil.getCurrentDate();
        //查询配置信息，并将配置信息封装到 dataFlow 对象中
        AppRoute appRoute = AppRouteCache.getAppRoute(dataFlow.getAppId());

        if (appRoute == null) {
            //添加耗时
            DataFlowFactory.addCostTime(dataFlow, "initConfigData", "加载配置耗时", startDate);
            throw new InitConfigDataException(ResponseConstant.RESULT_CODE_INNER_ERROR,"当前没有获取到AppId对应的信息");
        }
        dataFlow.setAppRoute(appRoute);
        //添加耗时
        DataFlowFactory.addCostTime(dataFlow, "initConfigData", "加载配置耗时", startDate);
    }

    /**
     * 3.0判断 AppId 是否 有serviceCode权限
     *
     * @param dataFlow
     * @throws RuntimeException
     */
    private void judgeAuthority(DataFlow dataFlow) throws NoAuthorityException {
        Date startDate = DateUtil.getCurrentDate();

        if (StringUtil.isNullOrNone(dataFlow.getAppId()) || dataFlow.getAppRoute() == null) {
            //添加耗时
            DataFlowFactory.addCostTime(dataFlow, "judgeAuthority", "鉴权耗时", startDate);
            throw new NoAuthorityException(ResponseConstant.RESULT_CODE_NO_AUTHORITY_ERROR, "appId 为空或不正确");
        }

        if (StringUtil.isNullOrNone(dataFlow.getTransactionId())) {
            //添加耗时
            DataFlowFactory.addCostTime(dataFlow, "judgeAuthority", "鉴权耗时", startDate);
            throw new NoAuthorityException(ResponseConstant.RESULT_CODE_NO_AUTHORITY_ERROR, "transactionId 不能为空");
        }

        if (StringUtil.isNullOrNone(dataFlow.getUserId())) {
            //添加耗时
            DataFlowFactory.addCostTime(dataFlow, "judgeAuthority", "鉴权耗时", startDate);
            throw new NoAuthorityException(ResponseConstant.RESULT_CODE_NO_AUTHORITY_ERROR, "userId 不能为空");
        }

        if (StringUtil.isNullOrNone(dataFlow.getRequestTime()) || DateUtil.judgeDate(dataFlow.getRequestTime(), DateUtil.DATE_FORMATE_STRING_DEFAULT)) {
            //添加耗时
            DataFlowFactory.addCostTime(dataFlow, "judgeAuthority", "鉴权耗时", startDate);
            throw new NoAuthorityException(ResponseConstant.RESULT_CODE_NO_AUTHORITY_ERROR, "requestTime 格式不对，遵循yyyyMMddHHmmss格式");
        }

        if (StringUtil.isNullOrNone(dataFlow.getOrderTypeCd())) {
            //添加耗时
            DataFlowFactory.addCostTime(dataFlow, "judgeAuthority", "鉴权耗时", startDate);
            throw new NoAuthorityException(ResponseConstant.RESULT_CODE_NO_AUTHORITY_ERROR, "orderTypeCd 不能为空");
        }

        //判断 AppId 是否有权限操作相应的服务
        if (dataFlow.getBusinesses() != null && dataFlow.getBusinesses().size() > 0) {
            for (Business business : dataFlow.getBusinesses()) {

                AppService appService = DataFlowFactory.getService(dataFlow, business.getServiceCode());

                //这里调用缓存 查询缓存信息
                if (appService == null) {
                    //添加耗时
                    DataFlowFactory.addCostTime(dataFlow, "judgeAuthority", "鉴权耗时", startDate);
                    throw new NoAuthorityException(ResponseConstant.RESULT_CODE_NO_AUTHORITY_ERROR, "AppId 没有权限访问 serviceCod = " + business.getServiceCode());
                }
            }
        }

        //检验白名单
        List<String> whileListIp = dataFlow.getAppRoute().getWhileListIp();
        if (whileListIp != null && !whileListIp.contains(dataFlow.getIp())) {
            //添加耗时
            DataFlowFactory.addCostTime(dataFlow, "judgeAuthority", "鉴权耗时", startDate);
            throw new NoAuthorityException(ResponseConstant.RESULT_CODE_NO_AUTHORITY_ERROR, "当前IP被限制不能访问服务");
        }

        //检查黑名单
        List<String> backListIp = dataFlow.getAppRoute().getBackListIp();
        if (backListIp != null && backListIp.contains(dataFlow.getIp())) {
            //添加耗时
            DataFlowFactory.addCostTime(dataFlow, "judgeAuthority", "鉴权耗时", startDate);
            throw new NoAuthorityException(ResponseConstant.RESULT_CODE_NO_AUTHORITY_ERROR, "当前IP被限制不能访问服务");
        }
        //添加耗时
        DataFlowFactory.addCostTime(dataFlow, "judgeAuthority", "鉴权耗时", startDate);
    }

    /**
     * 4.0规则校验
     *
     * @param dataFlow
     * @throws RuleException
     */
    private void ruleValidate(DataFlow dataFlow) throws RuleException {
        Date startDate = DateUtil.getCurrentDate();
        try {

            if (MappingConstant.VALUE_OFF.equals(MappingCache.getValue(MappingConstant.KEY_RULE_ON_OFF))
                    || (MappingCache.getValue(MappingConstant.KEY_NO_NEED_RULE_VALDATE_ORDER) != null
                    && MappingCache.getValue(MappingConstant.KEY_NO_NEED_RULE_VALDATE_ORDER).contains(dataFlow.getOrderTypeCd()))) {
                //不做校验
                //添加耗时
                DataFlowFactory.addCostTime(dataFlow, "ruleValidate", "规则校验耗时", startDate);
                return ;
            }

            //调用规则

        } catch (Exception e) {
            //添加耗时
            DataFlowFactory.addCostTime(dataFlow, "ruleValidate", "规则校验耗时", startDate);
            throw new RuleException(ResponseConstant.RESULT_CODE_RULE_ERROR, "规则校验异常失败：" + e.getMessage());
        }

        DataFlowFactory.addCostTime(dataFlow, "ruleValidate", "规则校验耗时", startDate);

    }

    /**
     * 5.0 保存订单和业务项 c_orders c_order_attrs c_business c_business_attrs
     *
     * @param dataFlow
     * @throws OrdersException
     */
    private void saveOrdersAndBusiness(DataFlow dataFlow) throws OrdersException {
        Date startDate = DateUtil.getCurrentDate();
        if(MappingCache.getValue(MappingConstant.KEY_NO_SAVE_ORDER) != null
                &&MappingCache.getValue(MappingConstant.KEY_NO_SAVE_ORDER).contains(dataFlow.getOrderTypeCd())){
            //不保存订单信息
            DataFlowFactory.addCostTime(dataFlow, "saveOrdersAndBusiness", "保存订单和业务项耗时", startDate);
            return ;
        }


        //1.0 保存 orders信息
        centerServiceDaoImpl.saveOrder(DataFlowFactory.getOrder(dataFlow));


        centerServiceDaoImpl.saveOrderAttrs(DataFlowFactory.getOrderAttrs(dataFlow));

        //2.0 保存 business信息

        centerServiceDaoImpl.saveBusiness(DataFlowFactory.getBusiness(dataFlow));

        centerServiceDaoImpl.saveBusinessAttrs(DataFlowFactory.getBusinessAttrs(dataFlow));

        DataFlowFactory.addCostTime(dataFlow, "saveOrdersAndBusiness", "保存订单和业务项耗时", startDate);
    }

    /**
     * 6.0 调用下游系统
     *
     * @param dataFlow
     * @throws BusinessException
     */
    private void invokeBusinessSystem(DataFlow dataFlow) throws BusinessException {
        Date startDate = DateUtil.getCurrentDate();
        if(MappingCache.getValue(MappingConstant.KEY_NO_INVOKE_BUSINESS_SYSTEM) != null
                &&MappingCache.getValue(MappingConstant.KEY_NO_INVOKE_BUSINESS_SYSTEM).contains(dataFlow.getOrderTypeCd())){
            //不用调用 下游系统的配置(一般不存在这种情况，这里主要是在没有下游系统的情况下测试中心服务用)
            DataFlowFactory.addCostTime(dataFlow, "invokeBusinessSystem", "调用下游系统耗时", startDate);
            return ;
        }

        //6.1 先处理同步方式的服务，每一同步后发布事件广播

        doSynchronousBusinesses(dataFlow);


        //6.2 处理异步服务
        doAsynchronousBusinesses(dataFlow);


        DataFlowFactory.addCostTime(dataFlow, "invokeBusinessSystem", "调用下游系统耗时", startDate);
    }



    /**
     * 7.0 作废订单和业务项
     *
     * @param dataFlow
     */
    private void invalidOrderAndBusiness(DataFlow dataFlow) {
        Date startDate = DateUtil.getCurrentDate();
        if(MappingCache.getValue(MappingConstant.KEY_NO_SAVE_ORDER) != null
                &&MappingCache.getValue(MappingConstant.KEY_NO_SAVE_ORDER).contains(dataFlow.getOrderTypeCd())){
            //不用作废订单信息
            DataFlowFactory.addCostTime(dataFlow, "invalidOrderAndBusiness", "作废订单和业务项耗时", startDate);
            return ;
        }

        //作废 订单
        centerServiceDaoImpl.updateOrder(DataFlowFactory.getNeedInvalidOrder(dataFlow));

        //作废订单项
        centerServiceDaoImpl.updateBusiness(DataFlowFactory.getNeedInvalidOrder(dataFlow));


        DataFlowFactory.addCostTime(dataFlow, "invalidOrderAndBusiness", "作废订单和业务项耗时", startDate);
    }


    /**
     * 8.0 广播作废已经完成业务系统订单信息
     *
     * @param dataFlow
     */
    private void invalidCompletedBusinessSystem(DataFlow dataFlow) throws Exception{
        // 根据 c_business 表中的字段business_type_cd 找到对应的消息队列名称
        List<Map> completedBusinesses = centerServiceDaoImpl.getCommonOrderCompledBusinessByBId(dataFlow.getBusinesses().get(0).getbId());
        for(AppServiceStatus serviceStatus :dataFlow.getAppRoute().getAppServices()){
            for(Map completedBusiness : completedBusinesses){
                if(completedBusiness.get("business_type_cd").equals(serviceStatus.getAppService().getBusinessTypeCd())){
                    KafkaFactory.sendKafkaMessage(serviceStatus.getAppService().getMessageQueueName(),"",
                            DataFlowFactory.getCompletedBusinessErrorJson(completedBusiness,serviceStatus.getAppService()));
                }
            }
        }

    }

    /**
     * 9.0 将订单状态改为失败，人工处理。
     *
     * @param dataFlow
     */
    private void updateOrderAndBusinessError(DataFlow dataFlow) {

        Date startDate = DateUtil.getCurrentDate();

        //作废 订单
        centerServiceDaoImpl.updateOrder(DataFlowFactory.getNeedErrorOrder(dataFlow));

        //作废订单项
        centerServiceDaoImpl.updateBusiness(DataFlowFactory.getNeedErrorOrder(dataFlow));


        DataFlowFactory.addCostTime(dataFlow, "updateOrderAndBusinessError", "订单状态改为失败耗时", startDate);

    }


    /**
     * 接受业务系统通知消息
     * @param receiveJson 接受报文
     * @throws SMOException
     */
    @Override
    public void receiveBusinessSystemNotifyMessage(String receiveJson) throws SMOException{
        DataFlow dataFlow = null;
        try {
            //1.0 创建数据流
            dataFlow = DataFlowFactory.newInstance().builder(receiveJson, null);
            //如果订单都没有保存，则再不要处理
            if(MappingCache.getValue(MappingConstant.KEY_NO_SAVE_ORDER) != null
                    &&MappingCache.getValue(MappingConstant.KEY_NO_SAVE_ORDER).contains(dataFlow.getOrderTypeCd())){
                //不保存订单信息
                return ;
            }

            //2.0加载数据，没有找到appId 及配置信息 则抛出InitConfigDataException
            reloadOrderInfoAndConfigData(dataFlow);

            //3.0 判断是否成功,失败会抛出BusinessStatusException异常
            judgeBusinessStatus(dataFlow);

            //4.0 修改业务为成功,如果发现业务项已经是作废或失败状态（D或E）则抛出BusinessException异常
            completeBusiness(dataFlow);
            //5.0当所有业务动作是否都是C，将订单信息改为 C 并且发布竣工消息，这里在广播之前确认
            completeOrderAndNotifyBusinessSystem(dataFlow);

        }catch (BusinessStatusException e){
            try {
                //6.0 作废订单和所有业务项
                invalidOrderAndBusiness(dataFlow);
                //7.0 广播作废业务系统订单信息
                //想法，这里只广播已经完成的订单项
                invalidCompletedBusinessSystem(dataFlow);
            } catch (Exception e1) {
                LoggerEngine.error("作废订单失败", e1);
                //8.0 将订单状态改为失败，人工处理。
                updateOrderAndBusinessError(dataFlow);
            }
        }catch (BusinessException e) {
            //9.0说明这个订单已经失败了，再不需要
            //想法，这里广播当前失败业务
            try {
                notifyBusinessSystemErrorMessage(dataFlow);
            }catch (Exception e1){
                //这里记录日志
            }
        }catch (InitConfigDataException e){ //这种一般不会出现，除非人工改了数据
            LoggerEngine.error("加载配置数据出错", e);
            try {
                //6.0 作废订单和所有业务项
                invalidOrderAndBusiness(dataFlow);
                //7.0 广播作废业务系统订单信息
                //想法，这里只广播已经完成的订单项
                invalidCompletedBusinessSystem(dataFlow);
            } catch (Exception e1) {
                LoggerEngine.error("作废订单失败", e1);
                //8.0 将订单状态改为失败，人工处理。
                updateOrderAndBusinessError(dataFlow);
            }

        }catch (Exception e){
            LoggerEngine.error("作废订单失败", e);
            //10.0 成功的情况下通知下游系统失败将状态改为NE，人工处理。
            updateBusinessNotifyError(dataFlow);
        }
    }


    /**
     * 2.0重新加载订单信息到dataFlow 中
     *
     * @param dataFlow
     */
    private void reloadOrderInfoAndConfigData(DataFlow dataFlow) {

        Map order = centerServiceDaoImpl.getOrderInfoByBId(dataFlow.getBusinesses().get(0).getbId());
        dataFlow.setoId(order.get("o_id").toString());
        //重新加载配置
        initConfigData(dataFlow);
    }

    /**
     * 9.0 成功的情况下通知下游系统失败将状态改为NE，人工处理。
     *
     * @param dataFlow
     */
    private void updateBusinessNotifyError(DataFlow dataFlow) {

        Date startDate = DateUtil.getCurrentDate();
            //完成订单项
        centerServiceDaoImpl.updateBusinessByBId(DataFlowFactory.getNeedNotifyErrorBusiness(dataFlow));

        DataFlowFactory.addCostTime(dataFlow, "updateBusinessNotifyError", "订单状态改为失败耗时", startDate);

    }

    /**
     * 判断是否都成功了
     * @param dataFlow
     */
    private void judgeBusinessStatus(DataFlow dataFlow) throws BusinessStatusException{

        List<Business> businesses = dataFlow.getBusinesses();

        for(Business business: businesses){
            if(!ResponseConstant.RESULT_CODE_SUCCESS.equals(business.getCode())){
                throw new BusinessStatusException(business.getCode(),"业务bId= "+business.getbId() + " 处理失败，需要作废订单");
            }
        }

    }

    /**
     * 3.0 修改业务为成功,如果发现业务项已经是作废或失败状态（D或E）则抛出BusinessException异常
     *
     * @param dataFlow
     */
    private void completeBusiness(DataFlow dataFlow) throws BusinessException{
        try {
            //完成订单项
            centerServiceDaoImpl.updateBusinessByBId(DataFlowFactory.getNeedCompleteBusiness(dataFlow));

        }catch (DAOException e){
            throw new BusinessException(e.getResult(),e);
        }
    }

    /**
     * //4.0当所有业务动作是否都是C，将订单信息改为 C 并且发布竣工消息，这里在广播之前确认
     * @param dataFlow
     */
    private void completeOrderAndNotifyBusinessSystem(DataFlow dataFlow) throws Exception{
        try {
            centerServiceDaoImpl.completeOrderByBId(DataFlowFactory.getMoreBId(dataFlow));
            //通知成功消息
            notifyBusinessSystemSuccessMessage(dataFlow);
        }catch (DAOException e){
            //这里什么都不做，说明订单没有完成
        }
    }

    /**
     * 通知 订单已经完成，后端需要完成数据
     * @param dataFlow
     */
    private void notifyBusinessSystemSuccessMessage(DataFlow dataFlow) throws Exception{

        //拼装报文通知业务系统
        KafkaFactory.sendKafkaMessage(
                DataFlowFactory.getService(dataFlow,dataFlow.getBusinesses().get(0).getServiceCode()).getMessageQueueName(),"",DataFlowFactory.getNotifyBusinessSuccessJson(dataFlow).toJSONString());

    }

    /**
     * 8.0 广播作废业务系统订单信息
     *
     * @param dataFlow
     */
    private void notifyBusinessSystemErrorMessage(DataFlow dataFlow) throws Exception{

        //拼装报文通知业务系统
        KafkaFactory.sendKafkaMessage(
                DataFlowFactory.getService(dataFlow,dataFlow.getBusinesses().get(0).getServiceCode()).getMessageQueueName(),"",DataFlowFactory.getNotifyBusinessErrorJson(dataFlow).toJSONString());
    }

    /**
     * 处理同步业务
     * @param dataFlow
     */
    private void doSynchronousBusinesses(DataFlow dataFlow) {
        List<Business> synchronousBusinesses = DataFlowFactory.getSynchronousBusinesses(dataFlow);
        //6.2处理同步服务
    }

    /**
     * 处理异步业务
     * @param
     */
    private void doAsynchronousBusinesses(DataFlow dataFlow) throws BusinessException{
        //6.3 处理异步，按消息队里处理
        List<Business> asynchronousBusinesses = DataFlowFactory.getAsynchronousBusinesses(dataFlow);
        try {
            for (Business business : asynchronousBusinesses) {
                KafkaFactory.sendKafkaMessage(DataFlowFactory.getService(dataFlow, business.getServiceCode()).getMessageQueueName(), "",
                        DataFlowFactory.getRequestBusinessJson(business).toJSONString());
            }
        }catch (Exception e){
            throw new BusinessException(ResponseConstant.RESULT_CODE_INNER_ERROR,e.getMessage());
        }
    }

    public ICenterServiceDAO getCenterServiceDaoImpl() {
        return centerServiceDaoImpl;
    }

    public void setCenterServiceDaoImpl(ICenterServiceDAO centerServiceDaoImpl) {
        this.centerServiceDaoImpl = centerServiceDaoImpl;
    }
}