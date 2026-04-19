package com.lingbo.ojcodesandbox;

import cn.hutool.core.io.resource.ResourceUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.lingbo.ojcodesandbox.model.ExecuteCodeRequest;
import com.lingbo.ojcodesandbox.model.ExecuteCodeResponse;
import com.lingbo.ojcodesandbox.model.ExecuteMessage;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class JavaDockerCodeSandbox extends JavaCodeSandboxTemplate {


    private static final Long TIME_OUT = 10000L;

    private static Boolean FIRST_INIT = true;

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

    /**
     * 3.创建容器，上传编译文件，容器内执行程序
     * @param userCodeFile 用户代码文件
     * @param inputList 输入列表
     * @return
     */
    @Override
    public List<ExecuteMessage> runFile(File userCodeFile, List<String> inputList) {
        String userCodeSaveParentPath = userCodeFile.getParentFile().getAbsolutePath();
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
                FIRST_INIT = false;
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
        hostConfig.withMemorySwap(0L);
        hostConfig.withCpuCount(1L);
        hostConfig.setBinds(new Bind(userCodeSaveParentPath, new Volume("/app")));

        CreateContainerResponse createContainerResponse = containerCmd
                .withHostConfig(hostConfig)
                .withAttachStdout(true)
                .withNetworkDisabled(true)
                .withReadonlyRootfs(true)
                .withAttachStderr(true)
                .withAttachStdin(true)
                .withTty(false)
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
        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        // 执行代码，并获取结果
        // docker exec keen_blackwell java -cp /app Main 1 3
        // 取运行时间的最大值用于判断是否超时
        long maxTime = 0;
        for (String inputArgs : inputList) {
            ExecuteMessage executeMessage = new ExecuteMessage();
            long time = 0L;

            StringBuilder messageBuilder = new StringBuilder();
            StringBuilder errorMessageBuilder = new StringBuilder();
            final long[] maxMemory = {0L};

            StopWatch stopWatch = new StopWatch();
            StatsCmd statsCmd = dockerClient.statsCmd(containerId);
            ResultCallback<Statistics> statisticsResultCallback = statsCmd.exec(
                    new ResultCallback<Statistics>() {
                        @Override
                        public void onNext(Statistics statistics) {
                            System.out.println("内存占用：" + statistics.getMemoryStats().getUsage() );
                            maxMemory[0] = Math.max(statistics.getMemoryStats().getUsage(), maxMemory[0]);
                        }

                        @Override
                        public void close() throws IOException {

                        }

                        @Override
                        public void onStart(Closeable closeable) {

                        }

                        @Override
                        public void onError(Throwable throwable) {

                        }

                        @Override
                        public void onComplete() {

                        }
                    }
            );
            String[] cmdArray = new String[]{"java", "-cp", "/app", "Main"};
            ExecCreateCmdResponse cmdResponse = dockerClient.execCreateCmd(containerId)
                    .withCmd(cmdArray)
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();
            String execId = cmdResponse.getId();
            ExecStartResultCallback execStartResultCallback = new ExecStartResultCallback() {
                @Override
                public void onNext(Frame frame) {
                    StreamType streamType = frame.getStreamType();

                    if (StreamType.STDERR.equals(streamType)) {
                        errorMessageBuilder.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                    }  else {
                        messageBuilder.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                    }

                    super.onNext(frame);
                }
            };
            try {
                stopWatch.start();
                boolean completed = dockerClient.execStartCmd(execId)
                        .withStdIn(new ByteArrayInputStream((inputArgs == null ? "" : inputArgs).getBytes(StandardCharsets.UTF_8)))
                        .exec(execStartResultCallback)
                        .awaitCompletion(TIME_OUT, TimeUnit.MILLISECONDS);
                stopWatch.stop();
                time = stopWatch.getLastTaskTimeMillis();
                executeMessage.setTime(time);
                maxTime = Math.max(time,maxTime);
                statsCmd.close();
                if (!completed) {
                    executeMessage.setErrorMessage("执行超时");
                } else {
                    executeMessage.setErrorMessage(errorMessageBuilder.toString().trim());
                }
            } catch (InterruptedException e) {
                System.out.println("程序执行异常");
                throw new RuntimeException(e);
            }
            try {
                InspectExecResponse inspectExecResponse = dockerClient.inspectExecCmd(execId).exec();
                if (inspectExecResponse != null && inspectExecResponse.getExitCodeLong() != null) {
                    executeMessage.setExitCode(inspectExecResponse.getExitCodeLong().intValue());
                }
            } catch (Exception ignored) {
            }
            executeMessage.setMessage(messageBuilder.toString().trim());
            executeMessageList.add(executeMessage);
        }
        return executeMessageList;
    }



}
