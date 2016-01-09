package com.tencent.business;

import com.tencent.common.Configure;
import com.tencent.common.Log;
import com.tencent.common.Signature;
import com.tencent.common.Util;
import com.tencent.common.report.ReporterFactory;
import com.tencent.common.report.protocol.ReportReqData;
import com.tencent.common.report.service.ReportService;
import com.tencent.protocol.pay_protocol.UnifiedOrderReqData;
import com.tencent.protocol.pay_protocol.UnifiedOrderResData;
import com.tencent.service.UnifiedOrderService;
import org.slf4j.LoggerFactory;

/**
 * Created by ouduobiao on 16/1/9.
 */
public class UnifiedOrderBusiness {
    //打log用
    private static Log log = new Log(LoggerFactory.getLogger(ScanPayBusiness.class));

    private UnifiedOrderService unifiedOrderService;


    public UnifiedOrderBusiness() throws IllegalAccessException, ClassNotFoundException, InstantiationException {
        unifiedOrderService = new UnifiedOrderService();
    }

    public interface ResultListener {

        //API返回ReturnCode不合法，支付请求逻辑错误，请仔细检测传过去的每一个参数是否合法，或是看API能否被正常访问
        void onFailByReturnCodeError(UnifiedOrderResData unifiedOrderResData);

        //API返回ReturnCode为FAIL，支付API系统返回失败，请检测Post给API的数据是否规范合法
        void onFailByReturnCodeFail(UnifiedOrderResData unifiedOrderResData);

        //支付请求API返回的数据签名验证失败，有可能数据被篡改了
        void onFailBySignInvalid(UnifiedOrderResData unifiedOrderResData);

        //用户余额不足，换其他卡支付或是用现金支付
        void onFailByMoneyNotEnough(UnifiedOrderResData unifiedOrderResData);

        //支付失败
        void onFail(UnifiedOrderResData unifiedOrderResData);

        //支付成功
        void onSuccess(UnifiedOrderResData unifiedOrderResData,String prepay_id);

    }


    /**
     * 直接执行统一下单业务逻辑（包含最佳实践流程）
     *
     * @param unifiedOrderReqData 这个数据对象里面包含了API要求提交的各种数据字段
     * @param resultListener 商户需要自己监听统一下单业务逻辑可能触发的各种分支事件，并做好合理的响应处理
     * @throws Exception
     */
    public void run(UnifiedOrderReqData unifiedOrderReqData, ResultListener resultListener) throws Exception {

        //--------------------------------------------------------------------
        //构造请求“统一下单API”所需要提交的数据
        //--------------------------------------------------------------------

        String outTradeNo = unifiedOrderReqData.getOut_trade_no();

        //接受API返回
        String payServiceResponseString;

        long costTimeStart = System.currentTimeMillis();


        log.i("统一下单API返回的数据如下：");
        payServiceResponseString = unifiedOrderService.request(unifiedOrderReqData);

        long costTimeEnd = System.currentTimeMillis();
        long totalTimeCost = costTimeEnd - costTimeStart;
        log.i("api请求总耗时：" + totalTimeCost + "ms");

        //打印回包数据
        log.i(payServiceResponseString);

        //将从API返回的XML数据映射到Java对象
        UnifiedOrderResData unifiedOrderResData = (UnifiedOrderResData) Util.getObjectFromXML(payServiceResponseString, UnifiedOrderResData.class);

        //异步发送统计请求
        //*

        ReportReqData reportReqData = new ReportReqData(
                unifiedOrderReqData.getDevice_info(),
                Configure.PAY_API,
                (int) (totalTimeCost),//本次请求耗时
                unifiedOrderResData.getReturn_code(),
                unifiedOrderResData.getReturn_msg(),
                unifiedOrderResData.getResult_code(),
                unifiedOrderResData.getErr_code(),
                unifiedOrderResData.getErr_code_des(),
                unifiedOrderReqData.getOut_trade_no(),
                unifiedOrderReqData.getSpbill_create_ip()
        );
        long timeAfterReport;
        if (Configure.isUseThreadToDoReport()) {
            ReporterFactory.getReporter(reportReqData).run();
            timeAfterReport = System.currentTimeMillis();
            log.i("prepay+report总耗时（异步方式上报）：" + (timeAfterReport - costTimeStart) + "ms");
        } else {
            ReportService.request(reportReqData);
            timeAfterReport = System.currentTimeMillis();
            log.i("prepay+report总耗时（同步方式上报）：" + (timeAfterReport - costTimeStart) + "ms");
        }

        if (unifiedOrderResData == null || unifiedOrderResData.getReturn_code() == null) {
            log.e("【统一下单失败】统一下单请求逻辑错误，请仔细检测传过去的每一个参数是否合法，或是看API能否被正常访问");
            resultListener.onFailByReturnCodeError(unifiedOrderResData);
            return;
        }

        if (unifiedOrderResData.getReturn_code().equals("FAIL")) {
            //注意：一般这里返回FAIL是出现系统级参数错误，请检测Post给API的数据是否规范合法
            log.e("【统一下单失败】统一下单API系统返回失败，请检测Post给API的数据是否规范合法");
            resultListener.onFailByReturnCodeFail(unifiedOrderResData);
            return;
        } else {
            log.i("统一下单API系统成功返回数据");
            //--------------------------------------------------------------------
            //收到API的返回数据的时候得先验证一下数据有没有被第三方篡改，确保安全
            //--------------------------------------------------------------------
            if (!Signature.checkIsSignValidFromResponseString(payServiceResponseString)) {
                log.e("【统一下单失败】统一下单请求API返回的数据签名验证失败，有可能数据被篡改了");
                resultListener.onFailBySignInvalid(unifiedOrderResData);
                return;
            }

            //获取错误码
            String errorCode = unifiedOrderResData.getErr_code();
            //获取错误描述
            String errorCodeDes = unifiedOrderResData.getErr_code_des();

            if (unifiedOrderResData.getResult_code().equals("SUCCESS")) {

                //--------------------------------------------------------------------
                //1)
                //--------------------------------------------------------------------

                log.i("【统一下单成功】");

                String prepayId = unifiedOrderResData.getPrepay_id();
                resultListener.onSuccess(unifiedOrderResData,prepayId);
            }else{

                //出现业务错误
                log.i("业务返回失败");
                log.i("err_code:" + errorCode);
                log.i("err_code_des:" + errorCodeDes);

                //业务错误时错误码有好几种，商户重点提示以下几种
                if (errorCode.equals("NOTENOUGH")) {
                    //提示用户余额不足，换其他卡支付或是用现金支付
                    log.w("【统一下单明确失败】原因是：" + errorCodeDes);
                    resultListener.onFailByMoneyNotEnough(unifiedOrderResData);
                }
                else {
                    //--------------------------------------------------------------------
                    //4)统一下单未知失败
                    //--------------------------------------------------------------------
                    resultListener.onFail(unifiedOrderResData);
                }
            }
        }
    }



}
