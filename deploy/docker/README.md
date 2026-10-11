# Healix Docker 单机部署（腾讯云轻量 2C4G）

一台服务器跑 MySQL + Redis + 后端 + Nginx。jar 与前端 dist 在本机构建后上传，服务器不装 JDK / Node。

## 首次部署

1. **服务器初始化**（Ubuntu 22.04）

   ```bash
   scp deploy/docker/server-init.sh ubuntu@<IP>:~
   ssh ubuntu@<IP> 'sudo bash ~/server-init.sh'
   ```

2. **服务器上创建 `.env`**

   ```bash
   scp deploy/docker/.env.example ubuntu@<IP>:/opt/healix/.env   # 若无权限先 sudo chown ubuntu /opt/healix
   ssh ubuntu@<IP>
   vi /opt/healix/.env   # 填密码、密钥（openssl rand -base64 32）、DeepSeek Key
   ```

3. **本机构建并发布**

   ```bash
   SERVER=ubuntu@<IP> ./deploy/docker/deploy.sh
   ```

4. **轻量服务器防火墙**放行 80、443；备案前临时放行 8081（管理端）、8082（手机工作台）。

5. **访问（备案前）**
   - C 端：`http://<IP>`
   - 管理端：`http://<IP>:8081`，Ops 账号为 `.env` 中的 `OPS_BOOTSTRAP_*`
   - 健管师/医生手机工作台：`http://<IP>:8082`

## 备案通过后切 HTTPS

1. 域名解析：`m.healixapp.com`、`admin.healixapp.com`、`staff.healixapp.com` → 服务器 IP（A 记录）
2. 腾讯云 SSL 证书申请免费证书，下载 **Nginx** 格式，放到服务器：

   ```text
   /opt/healix/certs/m.healixapp.com/fullchain.pem     ← xxx_bundle.crt
   /opt/healix/certs/m.healixapp.com/privkey.pem       ← xxx.key
   /opt/healix/certs/admin.healixapp.com/fullchain.pem
   /opt/healix/certs/admin.healixapp.com/privkey.pem
   /opt/healix/certs/staff.healixapp.com/fullchain.pem
   /opt/healix/certs/staff.healixapp.com/privkey.pem
   ```

3. `.env` 改 `NGINX_CONF=https`，然后 `cd /opt/healix && sudo docker compose up -d nginx`
4. 防火墙关闭 8081、8082
5. 页面底部展示备案号（前端构建时注入，未设置则不显示）：

   ```bash
   VITE_ICP_NO="沪ICP备XXXXXXXX号-1" VITE_PSB_NO="沪公网安备XXXXXXXXXXXXXX号" \
     SERVER=root@<IP> ./deploy/docker/deploy.sh
   ```

## 日常

服务器地址写在 `deploy/docker/deploy.local`（不入 Git）：`SERVER=root@<IP>`。

| 操作 | 命令 |
|---|---|
| 全部发布 | `./deploy/docker/deploy.sh` |
| 只发后端 / C 端 / 管理端 / 手机工作台 | `./deploy/docker/deploy.sh backend`、`user`、`admin`、`staff`（可组合） |
| 只同步 compose / nginx 配置 | `./deploy/docker/deploy.sh config` |
| 回滚到上一次发布 | `./deploy/docker/deploy.sh --rollback` |
| 查看线上版本 | `ssh root@<IP> cat /opt/healix/release/VERSION` |
| 看后端日志 | `sudo docker compose logs -f backend` |
| 状态 | `sudo docker compose ps` |
| 备份数据库 | `sudo docker exec healix-mysql sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" healix' > healix-$(date +%F).sql` |

数据目录：`/opt/healix/data/`（MySQL、Redis），重装或换机器前先备份。
