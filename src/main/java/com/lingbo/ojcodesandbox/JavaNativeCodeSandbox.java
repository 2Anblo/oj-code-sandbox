package com.lingbo.ojcodesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.StrUtil;
import com.lingbo.ojcodesandbox.model.ExecuteCodeRequest;
import com.lingbo.ojcodesandbox.model.ExecuteCodeResponse;
import com.lingbo.ojcodesandbox.model.ExecuteMessage;
import com.lingbo.ojcodesandbox.model.JudgeInfo;
import com.lingbo.ojcodesandbox.utils.ProcessUtils;
import org.springframework.util.StopWatch;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class JavaNativeCodeSandbox implements CodeSandbox {

    private static final String GLOBAL_CODE_DIR_NAME = "tmpCode";

    private static final String GLOBAL_JAVA_CLASS_NAME = "Main.java";

    public static void main(String[] args) {
        JavaNativeCodeSandbox codeSandbox = new JavaNativeCodeSandbox();
        String code = ResourceUtil.readStr("testcode/simpleComputeArgs/Main.java", StandardCharsets.UTF_8);

        ExecuteCodeRequest executeCodeRequest = new ExecuteCodeRequest();
        executeCodeRequest.setCode(code);
        executeCodeRequest.setInputList(Arrays.asList("1 2","2 3"));
        executeCodeRequest.setLanguage("java");
        ExecuteCodeResponse executeCodeResponse = codeSandbox.executeCode(executeCodeRequest);
        System.out.println(executeCodeResponse);
    }

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {

        // 1. 保存用户代码文件
        String code = executeCodeRequest.getCode();
        List<String> inputList = executeCodeRequest.getInputList();


        String userDir = System.getProperty("user.dir");
        String globalCodeSavePath = userDir + File.separator + GLOBAL_CODE_DIR_NAME;
        // 判断保存代码路径是否存在
        if (!FileUtil.exist(globalCodeSavePath)) {
            // 若不存在，则新建目录
            FileUtil.mkdir(globalCodeSavePath);
        }
        // 把用户代码隔离存放
        String userCodeSaveParentPath = globalCodeSavePath + File.separator + UUID.randomUUID().toString();
        String userCodeSavePath = userCodeSaveParentPath + File.separator + GLOBAL_JAVA_CLASS_NAME;
        // 将传入的代码写进Main.java文件
        File userCodeFile = FileUtil.writeString(code, userCodeSavePath, StandardCharsets.UTF_8);

        // 2.编译代码
        String compileCmd = String.format("javac -encoding utf-8 %s", userCodeFile.getAbsolutePath());
        try {
            Process compileProcess = Runtime.getRuntime().exec(compileCmd);
            ExecuteMessage executeMessage = ProcessUtils.runProcessAndGetMessage(compileProcess, "编译");
            System.out.println(executeMessage);
        } catch (IOException e) {
            return getErrorResponse(e);
        }

        // 3.执行代码，得到输出结果
        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        // 取运行时间的最大值用于判断是否超时
        long maxTime = 0;
        for (String inputArgs : inputList) {

            String runCmd = String.format("java -Dfile.encoding=UTF-8 -cp %s Main %s", userCodeSaveParentPath, inputArgs);
            try {
                Process runProcess = Runtime.getRuntime().exec(runCmd);
                ExecuteMessage executeMessage = ProcessUtils.runProcessAndGetMessage(runProcess, "运行");
                System.out.println(executeMessage);
                executeMessageList.add(executeMessage);
                Long time = executeMessage.getTime();
                if (time != null) {
                    maxTime = Math.max(maxTime, time);
                }

            } catch (IOException e) {
                return getErrorResponse(e);
            }
        }

        // 4.整理输出
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        List<String> outputList = new ArrayList<>();
        for (ExecuteMessage executeMessage : executeMessageList) {
            String message = executeMessage.getMessage();
            String errorMessage = executeMessage.getErrorMessage();
            if (StrUtil.isNotBlank(errorMessage)) {
                executeCodeResponse.setMessage(executeMessage.getErrorMessage());
                // 执行中存在错误
                executeCodeResponse.setStatus(3);
                break;
            }
            outputList.add(message);
        }
        // 正常运行完成
        if (outputList.size() == executeMessageList.size()) {
            executeCodeResponse.setStatus(1);
        }
        executeCodeResponse.setOutputList(outputList);
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setTime(maxTime);

        executeCodeResponse.setJudgeInfo(judgeInfo);

        // 5、文件清理
        if (userCodeFile.getParentFile() != null) {
            boolean del = FileUtil.del(userCodeSaveParentPath);
            System.out.println("删除" + (del ? "成功" : "失败"));
        }

        return executeCodeResponse;
    }

    /**
     * 获取错误响应
     *
     * @param e
     * @return
     */
    private ExecuteCodeResponse getErrorResponse(Throwable e) {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setOutputList(new ArrayList<>());
        executeCodeResponse.setMessage(e.getMessage());
        // 表示代码沙箱错误
        executeCodeResponse.setStatus(2);
        executeCodeResponse.setJudgeInfo(new JudgeInfo());
        return executeCodeResponse;
    }
}
