const { useState, useEffect, useCallback } = React;
const {
  ConfigProvider, theme, Layout, Typography, Segmented, Card, Input, Button, Form,
  Row, Col, Statistic, Table, Tag, Drawer, Descriptions, Result, Space, Empty,
  Modal, message, Alert, Spin
} = antd;
const Icons = window.icons || {};
const { Header, Content } = Layout;
const { Title, Text, Paragraph } = Typography;

const fmt = (ts) => (ts ? dayjs(ts).format('YYYY-MM-DD HH:mm') : '—');
const TOKEN_KEY = 'sayaka-admin-token';

function takeLoginTicket() {
  const hash = window.location.hash.startsWith('#') ? window.location.hash.slice(1) : '';
  const ticket = new URLSearchParams(hash).get('admin-login');
  if (ticket) window.history.replaceState(null, '', window.location.pathname + window.location.search);
  return ticket;
}

async function api(path, { method = 'GET', body, token } = {}) {
  const headers = {};
  if (body) headers['Content-Type'] = 'application/json';
  if (token) headers['X-Admin-Token'] = token;
  const res = await fetch(path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  let data = null;
  try { data = await res.json(); } catch (e) { /* 忽略非 JSON */ }
  if (!res.ok) {
    const err = new Error((data && data.error) || ('请求失败 (' + res.status + ')'));
    err.status = res.status;
    throw err;
  }
  return data;
}

const STATUS_META = {
  PENDING: { color: 'gold', label: '待处理' },
  APPROVED: { color: 'green', label: '已通过' },
  REJECTED: { color: 'red', label: '已驳回' },
};
function StatusTag({ status }) {
  const meta = STATUS_META[status] || { color: 'default', label: status || '—' };
  return <Tag color={meta.color}>{meta.label}</Tag>;
}

function EvidenceBlock({ punishment }) {
  if (!punishment) return null;
  return (
    <div>
      <Descriptions size="small" column={1} bordered
        items={[
          { key: 'p', label: '玩家', children: punishment.playerName },
          { key: 'c', label: '触发检测', children: punishment.checkDisplay },
          { key: 'vl', label: '违规值 VL', children: punishment.vl },
          { key: 'h', label: '封禁时长', children: punishment.hours + ' 小时（第 ' + punishment.banNumber + ' 次）' },
          { key: 'b', label: '封禁时间', children: fmt(punishment.bannedAt) },
          { key: 'e', label: '解封时间', children: fmt(punishment.expiresAt) },
          { key: 's', label: '当前状态', children: punishment.active ? <Tag color="volcano">封禁中</Tag> : <Tag>已到期</Tag> },
        ]} />
      {punishment.detections && punishment.detections.length > 0 && (
        <div style={{ marginTop: 16 }}>
          <Text strong>检测证据</Text>
          <Table size="small" rowKey={(_, i) => 'd' + i} pagination={false}
            style={{ marginTop: 8 }}
            dataSource={punishment.detections}
            columns={[
              { title: '时间', dataIndex: 'at', render: fmt, width: 150 },
              { title: '检测', dataIndex: 'check' },
              { title: 'VL', dataIndex: 'vl', width: 70 },
              { title: '详情', dataIndex: 'detail' },
            ]} />
        </div>
      )}
    </div>
  );
}

// ---------------- 申诉页 ----------------
function AppealView() {
  const [id, setId] = useState('');
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState(null); // { punishment, appeal }
  const [form] = Form.useForm();

  const lookup = useCallback(async () => {
    const value = id.trim();
    if (!value) { message.warning('请输入处罚 ID'); return; }
    setLoading(true); setResult(null);
    try {
      const data = await api('/api/appeal/lookup?id=' + encodeURIComponent(value));
      setResult(data);
    } catch (e) {
      message.error(e.message);
    } finally { setLoading(false); }
  }, [id]);

  const submit = useCallback(async (values) => {
    try {
      await api('/api/appeal/submit', { method: 'POST', body: { id: id.trim(), reason: values.reason, contact: values.contact || '' } });
      message.success('申诉已提交，请耐心等待管理员处理');
      form.resetFields();
      lookup();
    } catch (e) { message.error(e.message); }
  }, [id, lookup, form]);

  const appeal = result && result.appeal;
  const canSubmit = result && (!appeal || appeal.status === 'PENDING');

  return (
    <div style={{ maxWidth: 720, margin: '0 auto' }}>
      <Card>
        <Title level={4} style={{ marginTop: 0 }}>提交封禁申诉</Title>
        <Paragraph type="secondary">
          被反作弊系统封禁时，封禁界面上会显示一串“处罚 ID”。在下方输入该 ID 即可查看封禁详情并提交申诉。
        </Paragraph>
        <Space.Compact style={{ width: '100%' }}>
          <Input placeholder="输入处罚 ID，例如 7K3DM-Q9W2X" value={id}
            onChange={(e) => setId(e.target.value)} onPressEnter={lookup} allowClear />
          <Button type="primary" onClick={lookup} loading={loading}>查询</Button>
        </Space.Compact>
      </Card>

      {loading && <div style={{ textAlign: 'center', padding: 48 }}><Spin /></div>}

      {result && (
        <Card style={{ marginTop: 16 }}>
          <EvidenceBlock punishment={result.punishment} />

          {appeal && appeal.status !== 'PENDING' && (
            <Alert style={{ marginTop: 16 }}
              type={appeal.status === 'APPROVED' ? 'success' : 'error'} showIcon
              message={appeal.status === 'APPROVED' ? '你的申诉已通过' : '你的申诉已被驳回'}
              description={appeal.note ? ('管理员留言：' + appeal.note) : '如仍有疑问请联系管理员。'} />
          )}

          {appeal && appeal.status === 'PENDING' && (
            <Alert style={{ marginTop: 16 }} type="info" showIcon
              message="申诉处理中"
              description={'已于 ' + fmt(appeal.submittedAt) + ' 收到你的申诉，可在下方补充或更新申诉理由。'} />
          )}

          {canSubmit && (
            <Form form={form} layout="vertical" style={{ marginTop: 16 }} onFinish={submit}
              initialValues={{ reason: appeal ? appeal.reason : '' }}>
              <Form.Item name="reason" label="申诉理由"
                rules={[{ required: true, min: 5, message: '请填写至少 5 个字的申诉理由' }]}>
                <Input.TextArea rows={4} maxLength={2000} showCount
                  placeholder="请说明你认为此次判定为误判的理由，例如使用的客户端、当时的操作等。" />
              </Form.Item>
              <Form.Item name="contact" label="联系方式（选填）">
                <Input maxLength={200} placeholder="QQ / Discord / 邮箱，方便管理员回复" />
              </Form.Item>
              <Form.Item style={{ marginBottom: 0 }}>
                <Button type="primary" htmlType="submit">
                  {appeal ? '更新申诉' : '提交申诉'}
                </Button>
              </Form.Item>
            </Form>
          )}
        </Card>
      )}
    </div>
  );
}

// ---------------- 管理后台 ----------------
function AdminLogin({ onConnect }) {
  const [token, setToken] = useState('');
  const [loading, setLoading] = useState(false);
  const connect = async () => {
    if (!token.trim()) { message.warning('请输入管理令牌'); return; }
    setLoading(true);
    try {
      await api('/api/admin/overview', { token: token.trim() });
      onConnect(token.trim());
    } catch (e) {
      message.error(e.status === 401 ? '令牌无效' : e.message);
    } finally { setLoading(false); }
  };
  return (
    <div style={{ maxWidth: 420, margin: '48px auto 0' }}>
      <Card>
        <Title level={4} style={{ marginTop: 0 }}>管理员登录</Title>
        <Paragraph type="secondary">令牌在 config.yml 的 <Text code>web.admin-token</Text> 配置；留空时每次启动会随机生成并打印到服务器控制台。</Paragraph>
        <Space.Compact style={{ width: '100%' }}>
          <Input.Password placeholder="管理令牌" value={token}
            onChange={(e) => setToken(e.target.value)} onPressEnter={connect} />
          <Button type="primary" onClick={connect} loading={loading}>进入</Button>
        </Space.Compact>
      </Card>
    </div>
  );
}

function AdminDashboard({ token, onLogout }) {
  const [overview, setOverview] = useState(null);
  const [punishments, setPunishments] = useState([]);
  const [appeals, setAppeals] = useState([]);
  const [loading, setLoading] = useState(true);
  const [drawer, setDrawer] = useState(null);
  const [tab, setTab] = useState('punishments');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [o, p, a] = await Promise.all([
        api('/api/admin/overview', { token }),
        api('/api/admin/punishments', { token }),
        api('/api/admin/appeals', { token }),
      ]);
      setOverview(o);
      setPunishments(p.punishments || []);
      setAppeals(a.appeals || []);
    } catch (e) {
      if (e.status === 401) { message.error('令牌已失效，请重新登录'); onLogout(); }
      else message.error(e.message);
    } finally { setLoading(false); }
  }, [token, onLogout]);

  useEffect(() => { load(); }, [load]);

  const resolve = (record, approved) => {
    Modal.confirm({
      title: approved ? '通过该申诉并解封玩家？' : '驳回该申诉？',
      content: (
        <Form layout="vertical" style={{ marginTop: 12 }}>
          <Form.Item label="管理员留言（可选，玩家可见）">
            <Input.TextArea rows={3} id="resolve-note" placeholder="填写处理说明" />
          </Form.Item>
        </Form>
      ),
      okText: approved ? '通过并解封' : '驳回',
      okButtonProps: { danger: !approved },
      onOk: async () => {
        const note = (document.getElementById('resolve-note') || {}).value || '';
        await api('/api/admin/appeals/resolve', {
          method: 'POST', token,
          body: { id: record.punishmentId, approved, note },
        });
        message.success(approved ? '已通过并解封' : '已驳回');
        load();
      },
    });
  };

  const punishmentCols = [
    { title: '玩家', dataIndex: 'playerName', width: 130, fixed: 'left' },
    { title: '检测', dataIndex: 'checkDisplay', ellipsis: true },
    { title: 'VL', dataIndex: 'vl', width: 70 },
    { title: '时长(h)', dataIndex: 'hours', width: 80 },
    { title: '封禁时间', dataIndex: 'bannedAt', width: 150, render: fmt },
    { title: '状态', dataIndex: 'active', width: 90,
      render: (a) => a ? <Tag color="volcano">封禁中</Tag> : <Tag>已到期</Tag> },
    { title: '申诉', dataIndex: 'appealStatus', width: 90,
      render: (s) => s ? <StatusTag status={s} /> : <Text type="secondary">无</Text> },
    { title: '', key: 'op', width: 90, fixed: 'right',
      render: (_, r) => <Button size="small" onClick={() => setDrawer(r)}>详情</Button> },
  ];

  const appealCols = [
    { title: '玩家', dataIndex: 'playerName', width: 130, fixed: 'left' },
    { title: '申诉理由', dataIndex: 'reason', ellipsis: true },
    { title: '联系方式', dataIndex: 'contact', width: 160, ellipsis: true,
      render: (c) => c || <Text type="secondary">—</Text> },
    { title: '提交时间', dataIndex: 'submittedAt', width: 150, render: fmt },
    { title: '状态', dataIndex: 'status', width: 100, render: (s) => <StatusTag status={s} /> },
    { title: '操作', key: 'op', width: 200, fixed: 'right',
      render: (_, r) => (
        <Space size="small">
          <Button size="small" onClick={() => setDrawer(r.punishment)}>详情</Button>
          {r.status === 'PENDING' && <Button size="small" type="primary" onClick={() => resolve(r, true)}>通过</Button>}
          {r.status === 'PENDING' && <Button size="small" danger onClick={() => resolve(r, false)}>驳回</Button>}
        </Space>
      ) },
  ];

  return (
    <div>
      <Row gutter={16}>
        <Col xs={12} sm={8} md={6}><Card><Statistic title="在线玩家" value={overview && overview.onlinePlayers >= 0 ? overview.onlinePlayers : '—'} /></Card></Col>
        <Col xs={12} sm={8} md={6}><Card><Statistic title="启用检测" value={overview ? overview.enabledChecks : 0} suffix={'/ ' + (overview ? overview.totalChecks : 0)} /></Card></Col>
        <Col xs={12} sm={8} md={6}><Card><Statistic title="当前封禁" value={overview ? overview.activeBans : 0} /></Card></Col>
        <Col xs={12} sm={8} md={6}><Card><Statistic title="待处理申诉" valueStyle={{ color: overview && overview.pendingAppeals > 0 ? '#faad14' : undefined }} value={overview ? overview.pendingAppeals : 0} /></Card></Col>
      </Row>

      <Card style={{ marginTop: 16 }}
        tabList={[{ key: 'punishments', tab: '处罚记录 (' + punishments.length + ')' }, { key: 'appeals', tab: '申诉管理 (' + appeals.length + ')' }]}
        activeTabKey={tab} onTabChange={setTab}
        tabBarExtraContent={<Button onClick={load} loading={loading} icon={Icons.ReloadOutlined ? React.createElement(Icons.ReloadOutlined) : null}>刷新</Button>}>
        {tab === 'punishments'
          ? <Table rowKey="id" size="small" loading={loading} dataSource={punishments}
              columns={punishmentCols} scroll={{ x: 900 }}
              locale={{ emptyText: <Empty description="暂无处罚记录" /> }} />
          : <Table rowKey="punishmentId" size="small" loading={loading} dataSource={appeals}
              columns={appealCols} scroll={{ x: 900 }}
              locale={{ emptyText: <Empty description="暂无申诉" /> }} />}
      </Card>

      <Drawer width={560} open={!!drawer} onClose={() => setDrawer(null)} title="处罚详情">
        <EvidenceBlock punishment={drawer} />
      </Drawer>
    </div>
  );
}

function AdminView({ loginTicket, clearLoginTicket }) {
  const [token, setToken] = useState(() => localStorage.getItem(TOKEN_KEY) || '');
  const [exchanging, setExchanging] = useState(!!loginTicket);
  const connect = (t) => { localStorage.setItem(TOKEN_KEY, t); setToken(t); };
  const logout = () => { localStorage.removeItem(TOKEN_KEY); setToken(''); };
  useEffect(() => {
    if (!loginTicket) return;
    let active = true;
    api('/api/admin/login/exchange', { method: 'POST', body: { ticket: loginTicket } })
      .then((data) => {
        if (!active) return;
        localStorage.setItem(TOKEN_KEY, data.token);
        setToken(data.token);
      })
      .catch((e) => {
        if (active) message.error(e.status === 401 ? '一次性登录链接无效、已使用或已过期' : e.message);
      })
      .finally(() => {
        clearLoginTicket(null);
        if (active) setExchanging(false);
      });
    return () => { active = false; };
  }, [loginTicket, clearLoginTicket]);
  if (exchanging) {
    return <div style={{ textAlign: 'center', padding: 64 }}><Spin tip="正在登录管理后台…" /></div>;
  }
  return token
    ? <div>
        <div style={{ textAlign: 'right', marginBottom: 12 }}>
          <Button size="small" onClick={logout}>退出登录</Button>
        </div>
        <AdminDashboard token={token} onLogout={logout} />
      </div>
    : <AdminLogin onConnect={connect} />;
}

// ---------------- 应用根 ----------------
function App() {
  const [loginTicket, setLoginTicket] = useState(takeLoginTicket);
  const [view, setView] = useState(loginTicket ? 'admin' : 'appeal');
  const [dark, setDark] = useState(() => window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
  useEffect(() => {
    if (!window.matchMedia) return;
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const fn = (e) => setDark(e.matches);
    mq.addEventListener('change', fn);
    return () => mq.removeEventListener('change', fn);
  }, []);

  return (
    <ConfigProvider theme={{ algorithm: dark ? theme.darkAlgorithm : theme.defaultAlgorithm, token: { colorPrimary: '#d4380d' } }}>
      <Layout style={{ minHeight: '100%' }}>
        <Header style={{ display: 'flex', alignItems: 'center', gap: 16, background: dark ? '#141414' : '#d4380d' }}>
          <Text style={{ color: '#fff', fontSize: 18, fontWeight: 600 }}>Sayaka AntiCheat</Text>
          <Segmented value={view} onChange={setView} disabled={!!loginTicket}
            options={[{ label: '玩家申诉', value: 'appeal' }, { label: '管理后台', value: 'admin' }]} />
        </Header>
        <Content style={{ padding: 24 }}>
          {view === 'appeal' ? <AppealView /> : <AdminView loginTicket={loginTicket} clearLoginTicket={setLoginTicket} />}
        </Content>
      </Layout>
    </ConfigProvider>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App />);
