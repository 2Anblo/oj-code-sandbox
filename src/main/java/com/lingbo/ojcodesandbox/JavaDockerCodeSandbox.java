package com.lingbo.ojcodesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.dfa.FoundWord;
import cn.hutool.dfa.WordTree;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DockerClientBuilder;
import com.lingbo.ojcodesandbox.model.ExecuteCodeRequest;
import com.lingbo.ojcodesandbox.model.ExecuteCodeResponse;
import com.lingbo.ojcodesandbox.model.ExecuteMessage;
import com.lingbo.ojcodesandbox.model.JudgeInfo;
import com.lingbo.ojcodesandbox.utils.ProcessUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class JavaDockerCodeSandbox implements CodeSandbox {

    private static final String GLOBAL_CODE_DIR_NAME = "tmpCode";

    private static final String GLOBAL_JAVA_CLASS_NAME = "Main.java";

    private static final Long TIME_OUT = 10000L;

    private static final Boolean FIRST_INIT = true;

    public static void main(String[] args) {
        JavaDockerCodeSandbox codeSandbox = new JavaDockerCodeSandbox();
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

        // 3. 创建容器，上传编译文件
        // 获取默认的 Docker Client
        DockerClient dockerClient = DockerClientBuilder.getInstance().build();

        // 拉取镜像
        String image = "openjdk:8-alpine";
        if (FIRST_INIT) {

            PullImageCmd pullImageCmd = dockerClient.pullImageCmd(image);
            PullImageResultCallback pullImageResultCallback = new PullImageResultCallback(){
                @Override
                public void onNext(PullResponseItem item) {
                    System.out.println("下载镜像：" + item.getStatus());
                    super.onNext(item);
                }
            };
            try {
                pullImageCmd.exec(pullImageResultCallback)
                        .awaitCompletion();
            } catch (InterruptedException e) {
                System.out.println("拉取镜像异常");
                throw new RuntimeException(e);
            }
        }
        System.out.println("下载完成");

        // 创建容器
        CreateContainerCmd containerCmd = dockerClient.createContainerCmd(image);
        HostConfig hostConfig = new HostConfig();
        hostConfig.withMemory(100 * 1000 * 1000L);
        hostConfig.withCpuCount(1L);
        hostConfig.setBinds(new Bind(userCodeSaveParentPath, new Volume("/app")));

        CreateContainerResponse createContainerResponse = containerCmd
                .withHostConfig(hostConfig)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withAttachStdin(true)
                .withTty(true)
                .exec();
        System.out.println(createContainerResponse);
        String containerId = createContainerResponse.getId();

        // 获取容器列表，查看容器状态
        ListContainersCmd listContainersCmd = dockerClient.listContainersCmd();
        List<Container> containerList = listContainersCmd.withShowAll(true).exec();
        for (Container container : containerList) {
            System.out.println(container);
        }

        // 启动容器
        dockerClient.startContainerCmd(containerId).exec();




        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
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
