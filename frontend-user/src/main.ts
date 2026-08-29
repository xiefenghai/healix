import { createApp } from 'vue'
import {
  Button,
  Cell,
  CellGroup,
  Empty,
  Field,
  Form,
  Icon,
  NavBar,
  Popup,
  Tabbar,
  TabbarItem,
  Tag,
} from 'vant'
import 'vant/lib/index.css'
import App from './App.vue'
import router from './router'
import './style.css'

const app = createApp(App)
;[Button, Cell, CellGroup, Empty, Field, Form, Icon, NavBar, Popup, Tabbar, TabbarItem, Tag].forEach(
  (c) => app.use(c),
)
app.use(router).mount('#app')
