import { createApp } from 'vue'
import { createPinia } from 'pinia'
import naive from 'naive-ui'
import VXETable from 'vxe-table'
import 'vxe-table/lib/style.css'
import App from './App.vue'
import router from './router'
import '@/styles/tokens.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(naive)
app.use(VXETable)

app.mount('#app')
