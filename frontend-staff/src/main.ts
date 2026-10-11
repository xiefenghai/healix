import { createApp } from 'vue'
import {
  Badge,
  Button,
  Cell,
  CellGroup,
  Dialog,
  Empty,
  Field,
  Form,
  Icon,
  Loading,
  NavBar,
  NoticeBar,
  Popup,
  PullRefresh,
  Radio,
  RadioGroup,
  Search,
  Switch,
  Tab,
  Tabbar,
  TabbarItem,
  Tabs,
  Tag,
  Toast,
} from 'vant'
import 'vant/lib/index.css'
import App from './App.vue'
import router from './router'
import './style.css'

const app = createApp(App)
;[
  Badge,
  Button,
  Cell,
  CellGroup,
  Dialog,
  Empty,
  Field,
  Form,
  Icon,
  Loading,
  NavBar,
  NoticeBar,
  Popup,
  PullRefresh,
  Radio,
  RadioGroup,
  Search,
  Switch,
  Tab,
  Tabbar,
  TabbarItem,
  Tabs,
  Tag,
  Toast,
].forEach((c) => app.use(c))
app.use(router).mount('#app')
