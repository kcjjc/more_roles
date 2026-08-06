-- (可选) 测试数据 —— 密码明文仅用于本地测试, 生产环境请存 BCrypt 哈希值
-- ON CONFLICT: 用户名已存在就跳过, 避免重复执行脚本报错 (PG 的 upsert 语法)
INSERT INTO users (username, password)
VALUES ('ckj', '123456')
    ON CONFLICT (username) DO NOTHING;


-- (可选) 测试数据 —— 演示【一条 6000 字的人格信息, 拆成两条存储】
-- seq=0 存前 4000 字, seq=1 存后 2000 字; 两条共享同一个 persona_id='demo-persona-6000'
-- repeat('你', 4000) 生成 4000 个字符, 正好填满一片; 取出时按 seq 拼接即可还原 6000 字原文
INSERT INTO persona_fragment (user_id, persona_id, seq, name, content, status) VALUES
                                                                                   (1, 'demo-persona-6000', 0, '超长人格示例', repeat('你', 4000), 1),
                                                                                   (1, 'demo-persona-6000', 1, '超长人格示例', repeat('好', 2000), 1)
    ON CONFLICT (persona_id, seq) DO NOTHING;

-- 再插一条【单片的短人格】(内容没超过 4000 字, 只占一行, seq=0)
INSERT INTO persona_fragment (user_id, persona_id, seq, name, content, status) VALUES
    (1, 'demo-persona-short', 0, '猫娘助手', '你是一个温柔可爱的猫娘助手，说话喜欢在句尾加上“喵~”。', 1)
    ON CONFLICT (persona_id, seq) DO NOTHING;
