import { createBrowserRouter, Outlet } from 'react-router-dom';
import { AuthProvider } from '../features/auth/AuthContext';
import RootLayout from './RootLayout';
import LoginPage from '../features/auth/LoginPage';
import ConversationsPage from '../features/conversations/ConversationsPage';
import ThreadPage from '../features/messages/ThreadPage';

/**
 * AuthProvider sits above every route, /login included, so signIn can navigate on success and
 * the session restore runs once rather than once per protected route.
 */
export const router = createBrowserRouter([
  {
    element: (
      <AuthProvider>
        <Outlet />
      </AuthProvider>
    ),
    children: [
      { path: '/login', element: <LoginPage /> },
      {
        element: <RootLayout />,
        children: [
          { path: '/', element: <ConversationsPage /> },
          { path: '/c/:convId', element: <ThreadPage /> },
        ],
      },
    ],
  },
]);
