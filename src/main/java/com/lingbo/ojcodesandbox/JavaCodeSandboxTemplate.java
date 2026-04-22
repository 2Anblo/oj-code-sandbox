package com.lingbo.ojcodesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.lingbo.ojcodesandbox.model.ExecuteCodeRequest;
import com.lingbo.ojcodesandbox.model.ExecuteCodeResponse;
import com.lingbo.ojcodesandbox.model.ExecuteMessage;
import com.lingbo.ojcodesandbox.model.JudgeInfo;
import com.lingbo.ojcodesandbox.utils.ProcessUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;


/**
 * Java 代码沙箱模板方法的实现
 */
@Slf4j
public class JavaCodeSandboxTemplate implements CodeSandbox{


    private static final String GLOBAL_CODE_DIR_NAME = "tmpCode";

    private static final String GLOBAL_JAVA_CLASS_NAME = "Main.java";

    private static final Long TIME_OUT = 12000L;

    /**
     * 1. 保存用户代码文件
     * @param code 用户代码
     * @return 文件
     */
    public File saveCodeToFile(String code){

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
        return userCodeFile;
    }

    /**
     * 2. 编译代码
     * @param userCodeFile 用户代码文件
     * @return
     */
    public ExecuteMessage compileFile(File userCodeFile){
        String compileCmd = String.format("javac -encoding utf-8 %s", userCodeFile.getAbsolutePath());
        try {
            Process compileProcess = Runtime.getRuntime().exec(compileCmd);
            ExecuteMessage executeMessage = ProcessUtils.runProcessAndGetMessage(compileProcess, "编译");

            return executeMessage;
        } catch (IOException e) {
            ExecuteMessage errorMessage = new ExecuteMessage();
            errorMessage.setErrorMessage(e.getMessage());
            errorMessage.setExitCode(-1);
            return errorMessage;
        }
    }

    /**
     * 3. 执行文件，获得执行结果列表
     * @param userCodeFile 用户代码文件
     * @param inputList 输入列表
     * @return
     */
    public List<ExecuteMessage> runFile(File userCodeFile, List<String> inputList){
        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        String userCodeSaveParentPath = userCodeFile.getParentFile().getAbsolutePath();

        for (String inputArgs : inputList) {

            // 使用参数模式
            // String runCmd = String.format("java -Xmx256m -Dfile.encoding=UTF-8 -cp %s Main %s", userCodeSaveParentPath, inputArgs);
            // 使用ACM标准输入输出模式
            String runCmd = String.format("java -Xmx256m -Dfile.encoding=UTF-8 -cp %s Main", userCodeSaveParentPath);
            try {
                Process runProcess = Runtime.getRuntime().exec(runCmd);
                // 超时控制
                new Thread(()->{
                    try {
                        Thread.sleep(TIME_OUT);
                        System.out.println("超时了，中断");
                        runProcess.destroy();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }).start();
                // 使用参数模式
                // ExecuteMessage executeMessage = ProcessUtils.runProcessAndGetMessage(runProcess, "运行");
                // 使用ACM标准输入输出模式
                ExecuteMessage executeMessage = ProcessUtils.runInteractProcessAndGetMessage(runProcess, inputArgs);
                System.out.println(executeMessage);
                executeMessageList.add(executeMessage);


            } catch (IOException e) {
//                return getErrorResponse(e);
                throw new RuntimeException("执行错误",e);
            }

        }
        return executeMessageList;
    }

    /**
     * 4、获取输出结果
     * @param executeMessageList
     * @return
     */
    public ExecuteCodeResponse getOutputResponse(List<ExecuteMessage> executeMessageList){
        // 取运行时间的最大值用于判断是否超时
        long maxTime = 0;
        long maxMemory = 0;
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        List<String> outputList = new ArrayList<>();
        for (ExecuteMessage executeMessage : executeMessageList) {
            String message = executeMessage.getMessage();
            String errorMessage = executeMessage.getErrorMessage();
            Long time = executeMessage.getTime();
            if (time != null) {
                maxTime = Math.max(maxTime, time);
            }
            Long memory = executeMessage.getMemory();
            if (memory != null) {
                maxMemory = Math.max(maxMemory, memory);
            }
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
        if (maxMemory > 0) {
            judgeInfo.setMemory(maxMemory);
        }

        executeCodeResponse.setJudgeInfo(judgeInfo);
        return executeCodeResponse;
    }

    /**
     * 5、文件清理
     * @param userCodeFile
     * @return
     */
    public boolean deleteFile(File userCodeFile){
        if (userCodeFile.getParentFile() != null) {
            String userCodeSaveParentPath = userCodeFile.getParentFile().getAbsolutePath();
            boolean del = FileUtil.del(userCodeSaveParentPath);
            System.out.println("删除" + (del ? "成功" : "失败"));
            return del;
        }

        return true;
    }

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {

        String code = executeCodeRequest.getCode();
        List<String> inputList = executeCodeRequest.getInputList();
        if (inputList == null) {
            inputList = new ArrayList<>();
        }


        // 1. 保存用户代码文件
        File userCodeFile = saveCodeToFile(code);
        // 2. 编译代码
        ExecuteMessage compileFileExecuteMessage = compileFile(userCodeFile);
        if (compileFileExecuteMessage.getExitCode() != 0) {
            ExecuteCodeResponse errorResponse = new ExecuteCodeResponse();
            errorResponse.setOutputList(new ArrayList<>());
            errorResponse.setMessage(compileFileExecuteMessage.getErrorMessage());
            errorResponse.setStatus(3); // 3 = 编译/运行错误
            errorResponse.setJudgeInfo(new JudgeInfo());

            // 删除文件
            deleteFile(userCodeFile);

            return errorResponse;
        }

        // 3.执行代码，得到输出结果
        List<ExecuteMessage> executeMessageList = runFile(userCodeFile, inputList);

        // 4.整理输出
        ExecuteCodeResponse outputResponse = getOutputResponse(executeMessageList);

        // 5、文件清理
        boolean isDelete = deleteFile(userCodeFile);
        if (!isDelete) {
            log.error("delete file error, userCodeFilePath={}", userCodeFile.getAbsolutePath());
        }
        return outputResponse;
    }

    /**
     * 6、获取错误响应
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
