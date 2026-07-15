// Generated from src/web/app.jsx by npm run build:web.
(() => {
  // src/web/app.jsx
  var { useState, useEffect, useCallback } = React;
  var {
    ConfigProvider,
    theme,
    Layout,
    Typography,
    Segmented,
    Card,
    Input,
    Button,
    Form,
    Row,
    Col,
    Statistic,
    Table,
    Tag,
    Drawer,
    Descriptions,
    Result,
    Space,
    Empty,
    Modal,
    message,
    Alert,
    Spin
  } = antd;
  var Icons = window.icons || {};
  var { Header, Content } = Layout;
  var { Title, Text, Paragraph } = Typography;
  var fmt = (ts) => ts ? dayjs(ts).format("YYYY-MM-DD HH:mm") : "—";
  var TOKEN_KEY = "sayaka-admin-token";
  async function api(path, { method = "GET", body, token } = {}) {
    const headers = {};
    if (body) headers["Content-Type"] = "application/json";
    if (token) headers["X-Admin-Token"] = token;
    const res = await fetch(path, { method, headers, body: body ? JSON.stringify(body) : void 0 });
    let data = null;
    try {
      data = await res.json();
    } catch (e) {
    }
    if (!res.ok) {
      const err = new Error(data && data.error || "请求失败 (" + res.status + ")");
      err.status = res.status;
      throw err;
    }
    return data;
  }
  var STATUS_META = {
    PENDING: { color: "gold", label: "待处理" },
    APPROVED: { color: "green", label: "已通过" },
    REJECTED: { color: "red", label: "已驳回" }
  };
  function StatusTag({ status }) {
    const meta = STATUS_META[status] || { color: "default", label: status || "—" };
    return /* @__PURE__ */ React.createElement(Tag, { color: meta.color }, meta.label);
  }
  function EvidenceBlock({ punishment }) {
    if (!punishment) return null;
    return /* @__PURE__ */ React.createElement("div", null, /* @__PURE__ */ React.createElement(
      Descriptions,
      {
        size: "small",
        column: 1,
        bordered: true,
        items: [
          { key: "p", label: "玩家", children: punishment.playerName },
          { key: "c", label: "触发检测", children: punishment.checkDisplay },
          { key: "vl", label: "违规值 VL", children: punishment.vl },
          { key: "h", label: "封禁时长", children: punishment.hours + " 小时（第 " + punishment.banNumber + " 次）" },
          { key: "b", label: "封禁时间", children: fmt(punishment.bannedAt) },
          { key: "e", label: "解封时间", children: fmt(punishment.expiresAt) },
          { key: "s", label: "当前状态", children: punishment.active ? /* @__PURE__ */ React.createElement(Tag, { color: "volcano" }, "封禁中") : /* @__PURE__ */ React.createElement(Tag, null, "已到期") }
        ]
      }
    ), punishment.detections && punishment.detections.length > 0 && /* @__PURE__ */ React.createElement("div", { style: { marginTop: 16 } }, /* @__PURE__ */ React.createElement(Text, { strong: true }, "检测证据"), /* @__PURE__ */ React.createElement(
      Table,
      {
        size: "small",
        rowKey: (_, i) => "d" + i,
        pagination: false,
        style: { marginTop: 8 },
        dataSource: punishment.detections,
        columns: [
          { title: "时间", dataIndex: "at", render: fmt, width: 150 },
          { title: "检测", dataIndex: "check" },
          { title: "VL", dataIndex: "vl", width: 70 },
          { title: "详情", dataIndex: "detail" }
        ]
      }
    )));
  }
  function AppealView() {
    const [id, setId] = useState("");
    const [loading, setLoading] = useState(false);
    const [result, setResult] = useState(null);
    const [form] = Form.useForm();
    const lookup = useCallback(async () => {
      const value = id.trim();
      if (!value) {
        message.warning("请输入处罚 ID");
        return;
      }
      setLoading(true);
      setResult(null);
      try {
        const data = await api("/api/appeal/lookup?id=" + encodeURIComponent(value));
        setResult(data);
      } catch (e) {
        message.error(e.message);
      } finally {
        setLoading(false);
      }
    }, [id]);
    const submit = useCallback(async (values) => {
      try {
        await api("/api/appeal/submit", { method: "POST", body: { id: id.trim(), reason: values.reason, contact: values.contact || "" } });
        message.success("申诉已提交，请耐心等待管理员处理");
        form.resetFields();
        lookup();
      } catch (e) {
        message.error(e.message);
      }
    }, [id, lookup, form]);
    const appeal = result && result.appeal;
    const canSubmit = result && (!appeal || appeal.status === "PENDING");
    return /* @__PURE__ */ React.createElement("div", { style: { maxWidth: 720, margin: "0 auto" } }, /* @__PURE__ */ React.createElement(Card, null, /* @__PURE__ */ React.createElement(Title, { level: 4, style: { marginTop: 0 } }, "提交封禁申诉"), /* @__PURE__ */ React.createElement(Paragraph, { type: "secondary" }, "被反作弊系统封禁时，封禁界面上会显示一串“处罚 ID”。在下方输入该 ID 即可查看封禁详情并提交申诉。"), /* @__PURE__ */ React.createElement(Space.Compact, { style: { width: "100%" } }, /* @__PURE__ */ React.createElement(
      Input,
      {
        placeholder: "输入处罚 ID，例如 7K3DM-Q9W2X",
        value: id,
        onChange: (e) => setId(e.target.value),
        onPressEnter: lookup,
        allowClear: true
      }
    ), /* @__PURE__ */ React.createElement(Button, { type: "primary", onClick: lookup, loading }, "查询"))), loading && /* @__PURE__ */ React.createElement("div", { style: { textAlign: "center", padding: 48 } }, /* @__PURE__ */ React.createElement(Spin, null)), result && /* @__PURE__ */ React.createElement(Card, { style: { marginTop: 16 } }, /* @__PURE__ */ React.createElement(EvidenceBlock, { punishment: result.punishment }), appeal && appeal.status !== "PENDING" && /* @__PURE__ */ React.createElement(
      Alert,
      {
        style: { marginTop: 16 },
        type: appeal.status === "APPROVED" ? "success" : "error",
        showIcon: true,
        message: appeal.status === "APPROVED" ? "你的申诉已通过" : "你的申诉已被驳回",
        description: appeal.note ? "管理员留言：" + appeal.note : "如仍有疑问请联系管理员。"
      }
    ), appeal && appeal.status === "PENDING" && /* @__PURE__ */ React.createElement(
      Alert,
      {
        style: { marginTop: 16 },
        type: "info",
        showIcon: true,
        message: "申诉处理中",
        description: "已于 " + fmt(appeal.submittedAt) + " 收到你的申诉，可在下方补充或更新申诉理由。"
      }
    ), canSubmit && /* @__PURE__ */ React.createElement(
      Form,
      {
        form,
        layout: "vertical",
        style: { marginTop: 16 },
        onFinish: submit,
        initialValues: { reason: appeal ? appeal.reason : "" }
      },
      /* @__PURE__ */ React.createElement(
        Form.Item,
        {
          name: "reason",
          label: "申诉理由",
          rules: [{ required: true, min: 5, message: "请填写至少 5 个字的申诉理由" }]
        },
        /* @__PURE__ */ React.createElement(
          Input.TextArea,
          {
            rows: 4,
            maxLength: 2e3,
            showCount: true,
            placeholder: "请说明你认为此次判定为误判的理由，例如使用的客户端、当时的操作等。"
          }
        )
      ),
      /* @__PURE__ */ React.createElement(Form.Item, { name: "contact", label: "联系方式（选填）" }, /* @__PURE__ */ React.createElement(Input, { maxLength: 200, placeholder: "QQ / Discord / 邮箱，方便管理员回复" })),
      /* @__PURE__ */ React.createElement(Form.Item, { style: { marginBottom: 0 } }, /* @__PURE__ */ React.createElement(Button, { type: "primary", htmlType: "submit" }, appeal ? "更新申诉" : "提交申诉"))
    )));
  }
  function AdminLogin({ onConnect }) {
    const [token, setToken] = useState("");
    const [loading, setLoading] = useState(false);
    const connect = async () => {
      if (!token.trim()) {
        message.warning("请输入管理令牌");
        return;
      }
      setLoading(true);
      try {
        await api("/api/admin/overview", { token: token.trim() });
        onConnect(token.trim());
      } catch (e) {
        message.error(e.status === 401 ? "令牌无效" : e.message);
      } finally {
        setLoading(false);
      }
    };
    return /* @__PURE__ */ React.createElement("div", { style: { maxWidth: 420, margin: "48px auto 0" } }, /* @__PURE__ */ React.createElement(Card, null, /* @__PURE__ */ React.createElement(Title, { level: 4, style: { marginTop: 0 } }, "管理员登录"), /* @__PURE__ */ React.createElement(Paragraph, { type: "secondary" }, "令牌在 config.yml 的 ", /* @__PURE__ */ React.createElement(Text, { code: true }, "web.admin-token"), " 配置；留空时每次启动会随机生成并打印到服务器控制台。"), /* @__PURE__ */ React.createElement(Space.Compact, { style: { width: "100%" } }, /* @__PURE__ */ React.createElement(
      Input.Password,
      {
        placeholder: "管理令牌",
        value: token,
        onChange: (e) => setToken(e.target.value),
        onPressEnter: connect
      }
    ), /* @__PURE__ */ React.createElement(Button, { type: "primary", onClick: connect, loading }, "进入"))));
  }
  function AdminDashboard({ token, onLogout }) {
    const [overview, setOverview] = useState(null);
    const [punishments, setPunishments] = useState([]);
    const [appeals, setAppeals] = useState([]);
    const [loading, setLoading] = useState(true);
    const [drawer, setDrawer] = useState(null);
    const [tab, setTab] = useState("punishments");
    const load = useCallback(async () => {
      setLoading(true);
      try {
        const [o, p, a] = await Promise.all([
          api("/api/admin/overview", { token }),
          api("/api/admin/punishments", { token }),
          api("/api/admin/appeals", { token })
        ]);
        setOverview(o);
        setPunishments(p.punishments || []);
        setAppeals(a.appeals || []);
      } catch (e) {
        if (e.status === 401) {
          message.error("令牌已失效，请重新登录");
          onLogout();
        } else message.error(e.message);
      } finally {
        setLoading(false);
      }
    }, [token, onLogout]);
    useEffect(() => {
      load();
    }, [load]);
    const resolve = (record, approved) => {
      Modal.confirm({
        title: approved ? "通过该申诉并解封玩家？" : "驳回该申诉？",
        content: /* @__PURE__ */ React.createElement(Form, { layout: "vertical", style: { marginTop: 12 } }, /* @__PURE__ */ React.createElement(Form.Item, { label: "管理员留言（可选，玩家可见）" }, /* @__PURE__ */ React.createElement(Input.TextArea, { rows: 3, id: "resolve-note", placeholder: "填写处理说明" }))),
        okText: approved ? "通过并解封" : "驳回",
        okButtonProps: { danger: !approved },
        onOk: async () => {
          const note = (document.getElementById("resolve-note") || {}).value || "";
          await api("/api/admin/appeals/resolve", {
            method: "POST",
            token,
            body: { id: record.punishmentId, approved, note }
          });
          message.success(approved ? "已通过并解封" : "已驳回");
          load();
        }
      });
    };
    const punishmentCols = [
      { title: "玩家", dataIndex: "playerName", width: 130, fixed: "left" },
      { title: "检测", dataIndex: "checkDisplay", ellipsis: true },
      { title: "VL", dataIndex: "vl", width: 70 },
      { title: "时长(h)", dataIndex: "hours", width: 80 },
      { title: "封禁时间", dataIndex: "bannedAt", width: 150, render: fmt },
      {
        title: "状态",
        dataIndex: "active",
        width: 90,
        render: (a) => a ? /* @__PURE__ */ React.createElement(Tag, { color: "volcano" }, "封禁中") : /* @__PURE__ */ React.createElement(Tag, null, "已到期")
      },
      {
        title: "申诉",
        dataIndex: "appealStatus",
        width: 90,
        render: (s) => s ? /* @__PURE__ */ React.createElement(StatusTag, { status: s }) : /* @__PURE__ */ React.createElement(Text, { type: "secondary" }, "无")
      },
      {
        title: "",
        key: "op",
        width: 90,
        fixed: "right",
        render: (_, r) => /* @__PURE__ */ React.createElement(Button, { size: "small", onClick: () => setDrawer(r) }, "详情")
      }
    ];
    const appealCols = [
      { title: "玩家", dataIndex: "playerName", width: 130, fixed: "left" },
      { title: "申诉理由", dataIndex: "reason", ellipsis: true },
      {
        title: "联系方式",
        dataIndex: "contact",
        width: 160,
        ellipsis: true,
        render: (c) => c || /* @__PURE__ */ React.createElement(Text, { type: "secondary" }, "—")
      },
      { title: "提交时间", dataIndex: "submittedAt", width: 150, render: fmt },
      { title: "状态", dataIndex: "status", width: 100, render: (s) => /* @__PURE__ */ React.createElement(StatusTag, { status: s }) },
      {
        title: "操作",
        key: "op",
        width: 200,
        fixed: "right",
        render: (_, r) => /* @__PURE__ */ React.createElement(Space, { size: "small" }, /* @__PURE__ */ React.createElement(Button, { size: "small", onClick: () => setDrawer(r.punishment) }, "详情"), r.status === "PENDING" && /* @__PURE__ */ React.createElement(Button, { size: "small", type: "primary", onClick: () => resolve(r, true) }, "通过"), r.status === "PENDING" && /* @__PURE__ */ React.createElement(Button, { size: "small", danger: true, onClick: () => resolve(r, false) }, "驳回"))
      }
    ];
    return /* @__PURE__ */ React.createElement("div", null, /* @__PURE__ */ React.createElement(Row, { gutter: 16 }, /* @__PURE__ */ React.createElement(Col, { xs: 12, sm: 8, md: 6 }, /* @__PURE__ */ React.createElement(Card, null, /* @__PURE__ */ React.createElement(Statistic, { title: "在线玩家", value: overview && overview.onlinePlayers >= 0 ? overview.onlinePlayers : "—" }))), /* @__PURE__ */ React.createElement(Col, { xs: 12, sm: 8, md: 6 }, /* @__PURE__ */ React.createElement(Card, null, /* @__PURE__ */ React.createElement(Statistic, { title: "启用检测", value: overview ? overview.enabledChecks : 0, suffix: "/ " + (overview ? overview.totalChecks : 0) }))), /* @__PURE__ */ React.createElement(Col, { xs: 12, sm: 8, md: 6 }, /* @__PURE__ */ React.createElement(Card, null, /* @__PURE__ */ React.createElement(Statistic, { title: "当前封禁", value: overview ? overview.activeBans : 0 }))), /* @__PURE__ */ React.createElement(Col, { xs: 12, sm: 8, md: 6 }, /* @__PURE__ */ React.createElement(Card, null, /* @__PURE__ */ React.createElement(Statistic, { title: "待处理申诉", valueStyle: { color: overview && overview.pendingAppeals > 0 ? "#faad14" : void 0 }, value: overview ? overview.pendingAppeals : 0 })))), /* @__PURE__ */ React.createElement(
      Card,
      {
        style: { marginTop: 16 },
        tabList: [{ key: "punishments", tab: "处罚记录 (" + punishments.length + ")" }, { key: "appeals", tab: "申诉管理 (" + appeals.length + ")" }],
        activeTabKey: tab,
        onTabChange: setTab,
        tabBarExtraContent: /* @__PURE__ */ React.createElement(Button, { onClick: load, loading, icon: Icons.ReloadOutlined ? React.createElement(Icons.ReloadOutlined) : null }, "刷新")
      },
      tab === "punishments" ? /* @__PURE__ */ React.createElement(
        Table,
        {
          rowKey: "id",
          size: "small",
          loading,
          dataSource: punishments,
          columns: punishmentCols,
          scroll: { x: 900 },
          locale: { emptyText: /* @__PURE__ */ React.createElement(Empty, { description: "暂无处罚记录" }) }
        }
      ) : /* @__PURE__ */ React.createElement(
        Table,
        {
          rowKey: "punishmentId",
          size: "small",
          loading,
          dataSource: appeals,
          columns: appealCols,
          scroll: { x: 900 },
          locale: { emptyText: /* @__PURE__ */ React.createElement(Empty, { description: "暂无申诉" }) }
        }
      )
    ), /* @__PURE__ */ React.createElement(Drawer, { width: 560, open: !!drawer, onClose: () => setDrawer(null), title: "处罚详情" }, /* @__PURE__ */ React.createElement(EvidenceBlock, { punishment: drawer })));
  }
  function AdminView() {
    const [token, setToken] = useState(() => localStorage.getItem(TOKEN_KEY) || "");
    const connect = (t) => {
      localStorage.setItem(TOKEN_KEY, t);
      setToken(t);
    };
    const logout = () => {
      localStorage.removeItem(TOKEN_KEY);
      setToken("");
    };
    return token ? /* @__PURE__ */ React.createElement("div", null, /* @__PURE__ */ React.createElement("div", { style: { textAlign: "right", marginBottom: 12 } }, /* @__PURE__ */ React.createElement(Button, { size: "small", onClick: logout }, "退出登录")), /* @__PURE__ */ React.createElement(AdminDashboard, { token, onLogout: logout })) : /* @__PURE__ */ React.createElement(AdminLogin, { onConnect: connect });
  }
  function App() {
    const [view, setView] = useState("appeal");
    const [dark, setDark] = useState(() => window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches);
    useEffect(() => {
      if (!window.matchMedia) return;
      const mq = window.matchMedia("(prefers-color-scheme: dark)");
      const fn = (e) => setDark(e.matches);
      mq.addEventListener("change", fn);
      return () => mq.removeEventListener("change", fn);
    }, []);
    return /* @__PURE__ */ React.createElement(ConfigProvider, { theme: { algorithm: dark ? theme.darkAlgorithm : theme.defaultAlgorithm, token: { colorPrimary: "#d4380d" } } }, /* @__PURE__ */ React.createElement(Layout, { style: { minHeight: "100%" } }, /* @__PURE__ */ React.createElement(Header, { style: { display: "flex", alignItems: "center", gap: 16, background: dark ? "#141414" : "#d4380d" } }, /* @__PURE__ */ React.createElement(Text, { style: { color: "#fff", fontSize: 18, fontWeight: 600 } }, "Sayaka AntiCheat"), /* @__PURE__ */ React.createElement(
      Segmented,
      {
        value: view,
        onChange: setView,
        options: [{ label: "玩家申诉", value: "appeal" }, { label: "管理后台", value: "admin" }]
      }
    )), /* @__PURE__ */ React.createElement(Content, { style: { padding: 24 } }, view === "appeal" ? /* @__PURE__ */ React.createElement(AppealView, null) : /* @__PURE__ */ React.createElement(AdminView, null))));
  }
  ReactDOM.createRoot(document.getElementById("root")).render(/* @__PURE__ */ React.createElement(App, null));
})();
