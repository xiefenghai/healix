import { createApp } from 'vue'
import {
  Button,
  Cell,
  CellGroup,
  Checkbox,
  DatePicker,
  Dialog,
  Empty,
  Field,
  Form,
  Icon,
  Loading,
  NavBar,
  PickerGroup,
  Popup,
  Radio,
  RadioGroup,
  Search,
  Stepper,
  Switch,
  Tab,
  Tabbar,
  TabbarItem,
  Tabs,
  Tag,
  TimePicker,
} from 'vant'
import 'vant/lib/index.css'
import App from './App.vue'
import router from './router'
import './style.css'

const app = createApp(App)
;[
  Button,
  Cell,
  CellGroup,
  Checkbox,
  DatePicker,
  Dialog,
  Empty,
  Field,
  Form,
  Icon,
  Loading,
  NavBar,
  PickerGroup,
  Popup,
  Radio,
  RadioGroup,
  Search,
  Stepper,
  Switch,
  Tab,
  Tabbar,
  TabbarItem,
  Tabs,
  Tag,
  TimePicker,
].forEach((c) => app.use(c))
app.use(router).mount('#app')

if (import.meta.env.PROD) {
  void import('./shared/web-push').then((m) => m.registerServiceWorker())
}
