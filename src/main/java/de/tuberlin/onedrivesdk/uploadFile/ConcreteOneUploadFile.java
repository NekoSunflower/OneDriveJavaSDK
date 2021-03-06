package de.tuberlin.onedrivesdk.uploadFile;

import com.google.gson.Gson;
import de.tuberlin.onedrivesdk.OneDriveException;
import de.tuberlin.onedrivesdk.common.ConcreteOneDriveSDK;
import de.tuberlin.onedrivesdk.file.ConcreteOneFile;
import de.tuberlin.onedrivesdk.file.OneFile;
import de.tuberlin.onedrivesdk.folder.ConcreteOneFolder;
import de.tuberlin.onedrivesdk.networking.OneResponse;
import de.tuberlin.onedrivesdk.networking.PreparedRequest;
import de.tuberlin.onedrivesdk.networking.PreparedRequestMethod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Implementation of OneUploadFile, blocking operation
 */
public class ConcreteOneUploadFile implements OneUploadFile {

    private static final int                 chunkSize = 320 * 1024 * 100; // (use a multiple value of 320KB, best practice of dev.onedrive)
    private static final Logger              logger    = LogManager.getLogger(ConcreteOneUploadFile.class);
    private static final Gson                gson      = new Gson();
    private final        ReentrantLock       shouldRun = new ReentrantLock(true);
    private              File                fileToUpload;
    private              ConcreteOneFolder   parentFolder;
    private              String              fileName;
    private              ConcreteOneDriveSDK api;
    private              boolean             canceled  = false;
    private              boolean             finished  = false;
    private              UploadSession       uploadSession;
    private              RandomAccessFile    randFile;
    private              String              uploadUrl = "";

    public ConcreteOneUploadFile(ConcreteOneFolder parentFolder,
                                 File fileToUpload, ConcreteOneDriveSDK api) throws IOException, OneDriveException {
        this(parentFolder, fileToUpload, fileToUpload.getName(), api);
    }

    public ConcreteOneUploadFile(ConcreteOneFolder parentFolder,
                                 File fileToUpload, String fileName, ConcreteOneDriveSDK api) throws IOException, OneDriveException {
        try {
            checkNotNull(parentFolder);
            this.api = checkNotNull(api);
            this.parentFolder = parentFolder;
            this.fileName = fileName;
            if (fileToUpload != null) {
                if (fileToUpload.isFile()) {
                    if (fileToUpload.canRead()) {
                        this.fileToUpload = fileToUpload;
                        randFile = new RandomAccessFile(fileToUpload, "r");
                    } else {
                        throw new IOException(String.format("File %s is not readable!", fileToUpload.getName()));
                    }
                } else {
                    throw new IOException(String.format("%s is not a File",
                            fileToUpload.getAbsolutePath()));
                }
            } else {
                throw new NullPointerException("FileToUpload was null");
            }
            this.uploadSession = api.createUploadSession(parentFolder, fileName);
            this.uploadUrl = this.uploadSession.getUploadURL();
        } catch (Throwable e) {
            if (randFile != null) {
                randFile.close();
            }
            throw e;
        }
    }

    @Override
    public long fileSize() {
        return fileToUpload.length();
    }

    @Override
    public long uploadStatus() throws IOException, OneDriveException {
        if (uploadSession != null) {
            PreparedRequest request = new PreparedRequest(this.uploadUrl, PreparedRequestMethod.GET);
            OneResponse response = api.makeRequest(request);
            if (response.wasSuccess()) {
                return gson.fromJson(response.getBodyAsString(), UploadSession.class).getNextRange();
            } else {
                throw new OneDriveException(response.getBodyAsString());
            }
        }
        return 0;
    }

    @Override
    public OneFile startUpload() throws IOException, OneDriveException {
        try {

            byte[] bytes;
            OneFile finishedFile = null;
            OneResponse response;
            boolean lastChunkUploaded = false;
            int retry = 0;

            while (!canceled && !finished) {
                shouldRun.lock();
                try {
                    if (!lastChunkUploaded) {
                        try {
                            OneFile fileByPath = api.getFileByPath(parentFolder.getName() + "\\/" + fileName);
                            if (fileByPath.getSize() == fileToUpload.length()) {
                                finished = true;
                                finishedFile = fileByPath;
                                logger.info("currentUpload file [" + fileName + "] was already exists, aborting.");
                                break;
                            }
                        } catch (Throwable ignored) {
                        }
                        response = api.makeRequest(this.uploadUrl, PreparedRequestMethod.GET, null);

                        if (response.wasSuccess()) {
                            uploadSession = gson.fromJson(
                                    response.getBodyAsString(), UploadSession.class);
                            randFile.seek(uploadSession.getNextRange());
                            logger.info("Fetched updated uploadSession. Server requests {} as next chunk", uploadSession.getNextRange());
                        } else {
                            logger.info("Something went wrong while uploading. Was unable to fetch the currentUpload session from the Server");
                            throw new OneDriveException(
                                    String.format("Could not get current upload status from Server, aborting. Message was: %s", response.getBodyAsString()));
                        }
                    }
                    lastChunkUploaded = false;
                    long currFirstByte = randFile.getFilePointer();
                    PreparedRequest uploadChunk = new PreparedRequest(this.uploadUrl, PreparedRequestMethod.PUT);

                    if (currFirstByte + chunkSize < randFile.length()) {
                        bytes = new byte[chunkSize];
                    } else {
                        // optimistic cast, assuming the last bit of the file is
                        // never bigger than MAXINT
                        bytes = new byte[(int) (randFile.length() - randFile.getFilePointer())];
                    }
                    long start = randFile.getFilePointer();
                    randFile.readFully(bytes);

                    uploadChunk.setBody(bytes);
                    uploadChunk.addHeader("Content-Length", (randFile.getFilePointer() - start) + "");
                    uploadChunk.addHeader(
                            "Content-Range",
                            String.format("bytes %s-%s/%s", start, randFile.getFilePointer() - 1, randFile.length()));

                    logger.trace("Uploading chunk {} - {}", start, randFile.getFilePointer() - 1);
                    response = api.makeRequest(uploadChunk);
                    if (response.wasSuccess()) {
                        if (response.getStatusCode() == 200 || response.getStatusCode() == 201) { // if last chunk upload was successful end the
                            finished = true;
                            ConcreteOneFile oneFile = gson.fromJson(response.getBodyAsString(), ConcreteOneFile.class);
                            oneFile.setApi(api);
                            finishedFile = oneFile;
                        } else {
                            //just continue
                            uploadSession = gson.fromJson(response.getBodyAsString(), UploadSession.class);
                            randFile.seek(uploadSession.getNextRange());
                            lastChunkUploaded = true;
                            retry = 0;
                        }
                    } else {
                        logger.error(String.format("文件[" + fileName + "]断点续传出错，重试(" + ++retry + "/5)次, Uploading chunk %s - %s failed, Message was: %s", start, randFile.getFilePointer() - 1, response.getBodyAsString()));
                    }
                } catch (Throwable throwable) {
                    logger.error("文件[" + fileName + "]断点续传出错，重试(" + ++retry + "/5)次", throwable);
                }
                if (retry >= 5) {
                    canceled = true;
                    throw new OneDriveException("超出最大断点续传重试次数！");
                }
            }
            logger.info("finished upload [" + fileName + "]");
            return finishedFile;
        } finally {
            if (randFile != null) {
                randFile.close();
            }
        }
    }

    @Override
    public OneUploadFile pauseUpload() {
        logger.info("Pausing upload");
        shouldRun.lock();
        logger.info("Upload paused");
        return this;
    }

    @Override
    public OneUploadFile resumeUpload() {
        logger.info("Resuming upload");
        try {
            shouldRun.unlock();
            logger.info("Upload resumed");
        } catch (IllegalMonitorStateException e) {
            logger.info("Trying to resume an already running download");
        }
        return this;
    }

    @Override
    public OneUploadFile cancelUpload() throws IOException, OneDriveException {
        logger.info("Canceling upload");
        this.canceled = true;
        if (uploadSession != null) {
            api.makeRequest(this.uploadUrl,
                    PreparedRequestMethod.DELETE, "");
            logger.info("Upload was canceled");
        }
        return this;
    }

    @Override
    public File getUploadFile() {
        return this.fileToUpload;
    }

    @Override
    public OneFile call() throws IOException, OneDriveException {
        logger.info("Starting upload");
        return startUpload();
    }

}
