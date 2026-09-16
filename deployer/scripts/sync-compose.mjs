import { copyFileSync, mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
const dest = join(root, 'deployer', 'src-tauri', 'resources', 'docker-compose.yml')
mkdirSync(dirname(dest), { recursive: true })
copyFileSync(join(root, 'docker-compose.yml'), dest)
console.log(`synced docker-compose.yml -> ${dest}`)
