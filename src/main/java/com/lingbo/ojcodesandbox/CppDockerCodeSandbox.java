package com.lingbo.ojcodesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.lingbo.ojcodesandbox.model.ExecuteCodeRequest;
import com.lingbo.ojcodesandbox.model.ExecuteCodeResponse;
import com.lingbo.ojcodesandbox.model.ExecuteMessage;
import com.lingbo.ojcodesandbox.model.JudgeInfo;
import com.lingbo.ojcodesandbox.utils.DockerClientUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class CppDockerCodeSandbox implements CodeSandbox {

    private static final String GLOBAL_CODE_DIR_NAME = "tmpCode";

    private static final String GLOBAL_CPP_FILE_NAME = "Main.cpp";

    private static final String IMAGE = "gcc:12.2.0";

    private static final Long TIME_OUT = 10000L;

    private static Boolean FIRST_INIT = true;

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        String code = executeCodeRequest.getCode();
        List<String> inputList = executeCodeRequest.getInputList();
        if (inputList == null) {
            inputList = new ArrayList<>();
        }

        File userCodeFile = saveCodeToFile(code);
        DockerClient dockerClient = DockerClientUtils.getClient();
        String containerId = null;
        try {
            // 首次请求时预热镜像，避免每次拉取影响时延
            if (FIRST_INIT) {
                PullImageCmd pullImageCmd = dockerClient.pullImageCmd(IMAGE);
                PullImageResultCallback pullImageResultCallback = new PullImageResultCallback() {
                    @Override
                    public void onNext(PullResponseItem item) {
                        System.out.println("下载镜像：" + item.getStatus());
                        super.onNext(item);
                    }
                };
                boolean completed = pullImageCmd.exec(pullImageResultCallback).awaitCompletion(60, TimeUnit.SECONDS);
                if (!completed) {
                    throw new RuntimeException("拉取镜像超时：" + IMAGE);
                }
                FIRST_INIT = false;
            }
            System.out.println("镜像就绪：" + IMAGE);

            String userCodeSaveParentPath = userCodeFile.getParentFile().getAbsolutePath();
            HostConfig hostConfig = new HostConfig();
            hostConfig.withMemory(256 * 1000 * 1000L);
            hostConfig.withMemorySwap(0L);
            hostConfig.withCpuCount(1L);
            hostConfig.setBinds(new Bind(userCodeSaveParentPath, new Volume("/app")));
            hostConfig.withTmpFs(Collections.singletonMap("/tmp", "rw,noexec,nosuid,size=64m"));

            CreateContainerCmd containerCmd = dockerClient.createContainerCmd(IMAGE);
            CreateContainerResponse createContainerResponse = containerCmd
                    .withHostConfig(hostConfig)
                    .withAttachStdout(true)
                    .withNetworkDisabled(true)
                    .withReadonlyRootfs(true)
                    .withAttachStderr(true)
                    .withAttachStdin(true)
                    .withTty(false)
                    // 保持容器常驻，后续通过 exec 执行编译和运行
                    .withCmd("sh", "-c", "tail -f /dev/null")
                    .withWorkingDir("/app")
                    .exec();
            containerId = createContainerResponse.getId();
            System.out.println("容器创建成功：" + containerId);
            dockerClient.startContainerCmd(containerId).exec();
            System.out.println("容器启动成功：" + containerId);

            ExecuteMessage compileMessage = execInContainer(dockerClient, containerId,
                    new String[]{"g++", "-O2", "-std=c++17", "/app/Main.cpp", "-o", "/app/Main"},
                    null,
                    TIME_OUT);
            System.out.println("编译完成，exitCode=" + compileMessage.getExitCode());
            if (compileMessage.getExitCode() == null || compileMessage.getExitCode() != 0) {
                ExecuteCodeResponse errorResponse = new ExecuteCodeResponse();
                errorResponse.setOutputList(new ArrayList<>());
                errorResponse.setMessage(StrUtil.blankToDefault(compileMessage.getErrorMessage(), compileMessage.getMessage()));
                errorResponse.setStatus(3);
                errorResponse.setJudgeInfo(new JudgeInfo());
                return errorResponse;
            }

            List<ExecuteMessage> executeMessageList = new ArrayList<>();
            for (String inputArgs : inputList) {
                // ACM 模式：每组输入走一次标准输入，程序固定从 stdin 读取
                ExecuteMessage runMessage = execInContainer(dockerClient, containerId, new String[]{"/app/Main"}, inputArgs, TIME_OUT);
                executeMessageList.add(runMessage);
                if (StrUtil.isNotBlank(runMessage.getErrorMessage())) {
                    break;
                }
            }

            return getOutputResponse(executeMessageList);
        } catch (InterruptedException e) {
            ExecuteCodeResponse errorResponse = new ExecuteCodeResponse();
            errorResponse.setOutputList(new ArrayList<>());
            errorResponse.setMessage(e.getMessage());
            errorResponse.setStatus(2);
            errorResponse.setJudgeInfo(new JudgeInfo());
            return errorResponse;
        } catch (Exception e) {
            ExecuteCodeResponse errorResponse = new ExecuteCodeResponse();
            errorResponse.setOutputList(new ArrayList<>());
            errorResponse.setMessage(e.getMessage());
            errorResponse.setStatus(2);
            errorResponse.setJudgeInfo(new JudgeInfo());
            return errorResponse;
        } finally {
            deleteFile(userCodeFile);
            if (containerId != null) {
                try {
                    // 请求结束后强制删除容器，避免残留
                    dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private File saveCodeToFile(String code) {
        String userDir = System.getProperty("user.dir");
        String globalCodeSavePath = userDir + File.separator + GLOBAL_CODE_DIR_NAME;
        if (!FileUtil.exist(globalCodeSavePath)) {
            FileUtil.mkdir(globalCodeSavePath);
        }
        String userCodeSaveParentPath = globalCodeSavePath + File.separator + UUID.randomUUID().toString();
        String userCodeSavePath = userCodeSaveParentPath + File.separator + GLOBAL_CPP_FILE_NAME;
        return FileUtil.writeString(code, userCodeSavePath, StandardCharsets.UTF_8);
    }

    private boolean deleteFile(File userCodeFile) {
        if (userCodeFile == null || userCodeFile.getParentFile() == null) {
            return true;
        }
        String userCodeSaveParentPath = userCodeFile.getParentFile().getAbsolutePath();
        return FileUtil.del(userCodeSaveParentPath);
    }

    private ExecuteMessage execInContainer(DockerClient dockerClient, String containerId, String[] cmdArray, String stdin, Long timeoutMs) {
        ExecuteMessage executeMessage = new ExecuteMessage();
        StringBuilder messageBuilder = new StringBuilder();
        StringBuilder errorMessageBuilder = new StringBuilder();

        ExecCreateCmdResponse cmdResponse = dockerClient.execCreateCmd(containerId)
                .withCmd(cmdArray)
                .withAttachStdin(true)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();
        String execId = cmdResponse.getId();

        StopWatch stopWatch = new StopWatch();
        ExecStartResultCallback execStartResultCallback = new ExecStartResultCallback() {
            @Override
            public void onNext(Frame frame) {
                StreamType streamType = frame.getStreamType();
                if (StreamType.STDERR.equals(streamType)) {
                    errorMessageBuilder.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                } else {
                    messageBuilder.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                }
                super.onNext(frame);
            }
        };

        boolean completed = false;
        boolean timeout = false;
        try {
            stopWatch.start();
            String stdinToUse = stdin == null ? "" : stdin;
            if (StrUtil.isNotEmpty(stdinToUse) && !stdinToUse.endsWith("\n")) {
                stdinToUse = stdinToUse + "\n";
            }
            ByteArrayInputStream stdinStream = new ByteArrayInputStream(stdinToUse.getBytes(StandardCharsets.UTF_8));
            dockerClient.execStartCmd(execId)
                    .withStdIn(stdinStream)
                    .exec(execStartResultCallback);
            long startTime = System.currentTimeMillis();
            while (true) {
                InspectExecResponse inspectExecResponse = dockerClient.inspectExecCmd(execId).exec();
                // 兼容不同 docker-java 版本：优先取 running 标记，取不到时用 exitCode 是否为空兜底
                Boolean running = getRunningFlag(inspectExecResponse);
                if (running == null) {
                    Long exitCodeLong = null;
                    try {
                        exitCodeLong = inspectExecResponse == null ? null : inspectExecResponse.getExitCodeLong();
                    } catch (Throwable ignored) {
                    }
                    running = exitCodeLong == null;
                }
                if (!Boolean.TRUE.equals(running)) {
                    completed = true;
                    break;
                }
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    timeout = true;
                    break;
                }
                Thread.sleep(30);
            }
            stopWatch.stop();
        } catch (InterruptedException e) {
            errorMessageBuilder.append(e.getMessage());
        } finally {
            try {
                execStartResultCallback.awaitCompletion(1, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            try {
                execStartResultCallback.close();
            } catch (Exception ignored) {
            }
        }

        Integer exitCode = null;
        try {
            InspectExecResponse inspectExecResponse = dockerClient.inspectExecCmd(execId).exec();
            if (inspectExecResponse != null && inspectExecResponse.getExitCodeLong() != null) {
                exitCode = inspectExecResponse.getExitCodeLong().intValue();
            }
        } catch (Exception ignored) {
        }

        executeMessage.setExitCode(exitCode);
        executeMessage.setTime(stopWatch.getLastTaskTimeMillis());
        executeMessage.setMessage(messageBuilder.toString());
        if (timeout || !completed) {
            executeMessage.setErrorMessage("执行超时");
        } else {
            executeMessage.setErrorMessage(errorMessageBuilder.toString());
        }
        return executeMessage;
    }

    private Boolean getRunningFlag(InspectExecResponse inspectExecResponse) {
        if (inspectExecResponse == null) {
            return null;
        }
        try {
            // 新版常见命名
            java.lang.reflect.Method method = inspectExecResponse.getClass().getMethod("getRunning");
            Object value = method.invoke(inspectExecResponse);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
        } catch (Exception ignored) {
        }
        try {
            // 旧版常见命名
            java.lang.reflect.Method method = inspectExecResponse.getClass().getMethod("isRunning");
            Object value = method.invoke(inspectExecResponse);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private ExecuteCodeResponse getOutputResponse(List<ExecuteMessage> executeMessageList) {
        long maxTime = 0;
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        List<String> outputList = new ArrayList<>();
        for (ExecuteMessage executeMessage : executeMessageList) {
            String message = executeMessage.getMessage();
            String errorMessage = executeMessage.getErrorMessage();
            Long time = executeMessage.getTime();
            if (time != null) {
                maxTime = Math.max(maxTime, time);
            }
            if (StrUtil.isNotBlank(errorMessage)) {
                executeCodeResponse.setMessage(errorMessage);
                executeCodeResponse.setStatus(3);
                break;
            }
            outputList.add(message);
        }
        if (outputList.size() == executeMessageList.size()) {
            executeCodeResponse.setStatus(1);
        }
        executeCodeResponse.setOutputList(outputList);
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setTime(maxTime);
        executeCodeResponse.setJudgeInfo(judgeInfo);
        return executeCodeResponse;
    }
}
