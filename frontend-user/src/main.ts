import { createApp } from 'vue'
import {
  ActionSheet,
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
  Picker,
  PickerGroup,
  Popup,
  Radio,
  RadioGroup,
  Search,
  Slider,
  Stepper,
  Switch,
  Tab,
  Tabbar,
  TabbarItem,
  Tabs,
  Tag,
  TimePicker,
  showToast,
} from 'vant'
import 'vant/lib/index.css'
import App from './App.vue'
import router from './router'
import { syncAppViewportHeight } from './shared/app-viewport'
import './style.css'

/** 首屏前先写入真实可视高度，避免微信 iOS 首帧用错 100vh */
syncAppViewportHeight()

router.onError(() => {
  showToast('页面打开失败，请重试')
})

const app = createApp(App)
;[
  ActionSheet,
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
  Picker,
  PickerGroup,
  Popup,
  Radio,
  RadioGroup,
  Search,
  Slider,
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
