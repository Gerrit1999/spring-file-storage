package cn.xuyanwu.spring.file.storage.platform;

import cn.hutool.core.util.StrUtil;
import cn.xuyanwu.spring.file.storage.FileInfo;
import cn.xuyanwu.spring.file.storage.FileStorageProperties.QiniuKodoConfig;
import cn.xuyanwu.spring.file.storage.ProgressInputStream;
import cn.xuyanwu.spring.file.storage.ProgressListener;
import cn.xuyanwu.spring.file.storage.UploadPretreatment;
import cn.xuyanwu.spring.file.storage.exception.FileStorageRuntimeException;
import cn.xuyanwu.spring.file.storage.platform.QiniuKodoFileStorageClientFactory.QiniuKodoClient;
import com.qiniu.common.QiniuException;
import com.qiniu.storage.BucketManager;
import com.qiniu.storage.UploadManager;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Date;
import java.util.function.Consumer;

/**
 * 七牛云 Kodo 存储
 */
@Getter
@Setter
@NoArgsConstructor
public class QiniuKodoFileStorage implements FileStorage {
    private String platform;
    private String bucketName;
    private String domain;
    private String basePath;
    private FileStorageClientFactory<QiniuKodoClient> clientFactory;


    public QiniuKodoFileStorage(QiniuKodoConfig config,FileStorageClientFactory<QiniuKodoClient> clientFactory) {
        platform = config.getPlatform();
        bucketName = config.getBucketName();
        domain = config.getDomain();
        basePath = config.getBasePath();
        this.clientFactory = clientFactory;
    }

    public QiniuKodoClient getClient() {
        return clientFactory.getClient();
    }


    @Override
    public void close() {
        clientFactory.close();
    }

    public String getFileKey(FileInfo fileInfo) {
        return fileInfo.getBasePath() + fileInfo.getPath() + fileInfo.getFilename();
    }

    public String getThFileKey(FileInfo fileInfo) {
        if (StrUtil.isBlank(fileInfo.getThFilename())) return null;
        return fileInfo.getBasePath() + fileInfo.getPath() + fileInfo.getThFilename();
    }

    @Override
    public boolean save(FileInfo fileInfo,UploadPretreatment pre) {
        fileInfo.setBasePath(basePath);
        String newFileKey = getFileKey(fileInfo);
        fileInfo.setUrl(domain + newFileKey);
        if (fileInfo.getFileAcl() != null) {
            throw new FileStorageRuntimeException("文件上传失败，七牛云 Kodo 不支持设置 ACL！platform：" + platform + "，filename：" + fileInfo.getOriginalFilename());
        }
        ProgressListener listener = pre.getProgressListener();

        try (InputStream in = pre.getFileWrapper().getInputStream()) {
            //七牛云 Kodo 的 SDK 内部会自动分片上传
            QiniuKodoClient client = getClient();
            UploadManager uploadManager = client.getUploadManager();
            String token = client.getAuth().uploadToken(bucketName);
            uploadManager.put(listener == null ? in : new ProgressInputStream(in,listener,fileInfo.getSize()),
                    newFileKey,token,null,fileInfo.getContentType());

            byte[] thumbnailBytes = pre.getThumbnailBytes();
            if (thumbnailBytes != null) { //上传缩略图
                String newThFileKey = getThFileKey(fileInfo);
                fileInfo.setThUrl(domain + newThFileKey);
                uploadManager.put(new ByteArrayInputStream(thumbnailBytes),newThFileKey,token,null,fileInfo.getThContentType());
            }

            return true;
        } catch (IOException e) {
            try {
                getClient().getBucketManager().delete(bucketName,newFileKey);
            } catch (QiniuException ignored) {
            }
            throw new FileStorageRuntimeException("文件上传失败！platform：" + platform + "，filename：" + fileInfo.getOriginalFilename(),e);
        }
    }

    @Override
    public boolean isSupportPresignedUrl() {
        return true;
    }

    @Override
    public String generatePresignedUrl(FileInfo fileInfo,Date expiration) {
        int deadline = (int) (expiration.getTime() / 1000);
        return getClient().getAuth().privateDownloadUrlWithDeadline(fileInfo.getUrl(),deadline);
    }

    @Override
    public String generateThPresignedUrl(FileInfo fileInfo,Date expiration) {
        if (StrUtil.isBlank(fileInfo.getThUrl())) return null;
        int deadline = (int) (expiration.getTime() / 1000);
        return getClient().getAuth().privateDownloadUrlWithDeadline(fileInfo.getThUrl(),deadline);
    }

    @Override
    public boolean delete(FileInfo fileInfo) {
        BucketManager manager = getClient().getBucketManager();
        try {
            if (fileInfo.getThFilename() != null) {   //删除缩略图
                delete(manager,getThFileKey(fileInfo));
            }
            delete(manager,getFileKey(fileInfo));
        } catch (QiniuException e) {
            throw new FileStorageRuntimeException("删除文件失败！" + e.code() + "，" + e.response.toString(),e);
        }
        return true;
    }

    public void delete(BucketManager manager,String filename) throws QiniuException {
        try {
            manager.delete(bucketName,filename);
        } catch (QiniuException e) {
            if (!(e.response != null && e.response.statusCode == 612)) {
                throw e;
            }
        }
    }


    @Override
    public boolean exists(FileInfo fileInfo) {
        BucketManager manager = getClient().getBucketManager();
        try {
            com.qiniu.storage.model.FileInfo stat = manager.stat(bucketName,getFileKey(fileInfo));
            if (stat != null && (StrUtil.isNotBlank(stat.md5) || StrUtil.isNotBlank(stat.hash))) return true;
        } catch (QiniuException e) {
            if (e.code() == 612) return false;
            throw new FileStorageRuntimeException("查询文件是否存在失败！" + e.code() + "，" + e.response.toString(),e);
        }
        return false;
    }

    @Override
    public void download(FileInfo fileInfo,Consumer<InputStream> consumer) {
        String url = getClient().getAuth().privateDownloadUrl(fileInfo.getUrl());
        try (InputStream in = new URL(url).openStream()) {
            consumer.accept(in);
        } catch (IOException e) {
            throw new FileStorageRuntimeException("文件下载失败！fileInfo：" + fileInfo,e);
        }
    }

    @Override
    public void downloadTh(FileInfo fileInfo,Consumer<InputStream> consumer) {
        if (StrUtil.isBlank(fileInfo.getThUrl())) {
            throw new FileStorageRuntimeException("缩略图文件下载失败，文件不存在！fileInfo：" + fileInfo);
        }
        String url = getClient().getAuth().privateDownloadUrl(fileInfo.getThUrl());
        try (InputStream in = new URL(url).openStream()) {
            consumer.accept(in);
        } catch (IOException e) {
            throw new FileStorageRuntimeException("缩略图文件下载失败！fileInfo：" + fileInfo,e);
        }
    }


}
