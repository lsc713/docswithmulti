import RequireRole from './RequireRole'

export default function RequireAdmin({ children }) {
  return <RequireRole roles={['ADMIN']}>{children}</RequireRole>
}
