import React from 'react'
import { AppProvider } from './stores/app'
import { StudioLayout, Loading } from './components/ui'
import { useHashRoute } from './router'
import StartPage from './features/database/StartPage'
import DatabasePage from './features/table/DatabasePage'
import SqlPage from './features/sql/SqlPage'
import IndexPlanPage from './features/index/IndexPlanPage'
import StorageLab from './features/labs/StorageLab'
import SqlPipelineLab from './features/labs/SqlPipelineLab'
import TxnLab from './features/labs/TxnLab'
import RecoveryLab from './features/labs/RecoveryLab'
import PerfLab from './features/labs/PerfLab'
import JdbcLab from './features/labs/JdbcLab'
import HelpPage from './features/database/HelpPage'

function Router({ route }: { route: string }) {
  switch (route) {
    case '/start': return <StartPage />
    case '/db': return <DatabasePage />
    case '/sql': return <SqlPage />
    case '/index': return <IndexPlanPage />
    case '/lab/storage': return <StorageLab />
    case '/lab/pipeline': return <SqlPipelineLab />
    case '/lab/txn': return <TxnLab />
    case '/lab/recovery': return <RecoveryLab />
    case '/lab/perf': return <PerfLab />
    case '/lab/jdbc': return <JdbcLab />
    case '/help': return <HelpPage />
    default: return <StartPage />
  }
}

export default function App() {
  const route = useHashRoute()
  return (
    <AppProvider>
      <React.Suspense fallback={<Loading />}>
        <StudioLayout route={route}>
          <Router route={route} />
        </StudioLayout>
      </React.Suspense>
    </AppProvider>
  )
}
