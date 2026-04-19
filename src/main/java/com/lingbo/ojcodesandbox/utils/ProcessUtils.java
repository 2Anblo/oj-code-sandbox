package com.lingbo.ojcodesandbox.utils;

import cn.hutool.core.util.StrUtil;
import com.lingbo.ojcodesandbox.model.ExecuteMessage;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.StopWatch;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 进程工具类
 */
public class ProcessUtils {

    /**
     * 执行进程并获取信息
     *
     * @param runProcess 执行进程
     * @param opName 操作名
     * @return 进程返回消息
     */
    public static ExecuteMessage runProcessAndGetMessage(Process runProcess, String opName) {
        ExecuteMessage executeMessage = new ExecuteMessage();
        try {

            StopWatch stopWatch = new StopWatch();
            stopWatch.start();
            // 等待程序执行，获取错误码
            int exitValue = runProcess.waitFor();
            executeMessage.setExitCode(exitValue);

            // 正常退出，退出码为0
            if (exitValue == 0) {
                System.out.println(opName + "成功！");
                // 分批获取进程的正常输出
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(runProcess.getInputStream()));

                List<String> outputStrList = new ArrayList<>();
                // 逐行读取
                String compileOutputLine;
                while ((compileOutputLine = bufferedReader.readLine()) != null) {
                    outputStrList.add(compileOutputLine);
                }
                executeMessage.setMessage(StringUtils.join(outputStrList,"\n"));

            } else {
                // 异常退出
                System.out.println(opName + "失败，错误码： " + exitValue);
                // 分批获取进程的正常输出
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(runProcess.getInputStream()));
                List<String> outputStrList = new ArrayList<>();
                // 逐行读取
                String compileOutputLine;
                while ((compileOutputLine = bufferedReader.readLine()) != null) {
                    outputStrList.add(compileOutputLine);
                }
                executeMessage.setMessage(StringUtils.join(outputStrList,"\n"));

                // 分批获取进程的错误输出
                BufferedReader errorBufferedReader = new BufferedReader(new InputStreamReader(runProcess.getErrorStream()));
                List<String> errorOutputStrList = new ArrayList<>();
                // 逐行读取
                String errorOutputLine;
                while ((errorOutputLine = errorBufferedReader.readLine()) != null) {
                    errorOutputStrList.add(errorOutputLine);
                }
                executeMessage.setErrorMessage(StringUtils.join(errorOutputStrList,"\n"));

            }
            stopWatch.stop();
            long lastTaskTimeMillis = stopWatch.getLastTaskTimeMillis();
            executeMessage.setTime(lastTaskTimeMillis);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return executeMessage;
    }


    /**
     * 执行交互式进程并获取信息
     *
     * @param runProcess
     * @param input
     * @return
     */
    public static ExecuteMessage runInteractProcessAndGetMessage(Process runProcess, String input) {
        ExecuteMessage executeMessage = new ExecuteMessage();
        StopWatch stopWatch = new StopWatch();

        try {
            stopWatch.start();

            // 1. 写入标准输入
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(runProcess.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write(input);
                writer.flush();
                // try-with-resources 结束后自动 close，向子进程发送 EOF
            }

            // 2. 等待执行结束
            int exitValue = runProcess.waitFor();
            executeMessage.setExitCode(exitValue);

            // 3. 读取标准输出
            StringBuilder outputBuilder = new StringBuilder();
            try (BufferedReader bufferedReader = new BufferedReader(
                    new InputStreamReader(runProcess.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    outputBuilder.append(line).append("\n");
                }
            }
            executeMessage.setMessage(outputBuilder.toString().trim());

            // 4. 读取错误输出
            StringBuilder errorBuilder = new StringBuilder();
            try (BufferedReader errorBufferedReader = new BufferedReader(
                    new InputStreamReader(runProcess.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = errorBufferedReader.readLine()) != null) {
                    errorBuilder.append(line).append("\n");
                }
            }
            executeMessage.setErrorMessage(errorBuilder.toString().trim());

            if (exitValue == 0) {
                System.out.println("运行成功！");
            } else {
                System.out.println("运行失败，错误码：" + exitValue);
            }

        } catch (Exception e) {
            e.printStackTrace();
            executeMessage.setErrorMessage(e.getMessage());
        } finally {
            stopWatch.stop();
            executeMessage.setTime(stopWatch.getTotalTimeMillis());
            runProcess.destroy();
        }

        return executeMessage;
    }

}
