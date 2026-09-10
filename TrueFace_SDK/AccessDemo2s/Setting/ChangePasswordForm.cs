using NetSDKCS;
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Data;
using System.Drawing;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace AccessDemo2s
{
    public partial class ChangePasswordForm : Form
    {
        private IntPtr m_LoginID = IntPtr.Zero;

        public ChangePasswordForm()
        {
            InitializeComponent();
        }

        public ChangePasswordForm(IntPtr loginid)
        {
            InitializeComponent();
            m_LoginID = loginid;
        }

        private void btn_Change_Click(object sender, EventArgs e)
        {
            #region Modify Password 
            if (txt_Name.Text == null || txt_Name.Text == "")
            {
                MessageBox.Show("Please input user name()");
                return;
            }
            if (txt_OldPwd.Text == null || txt_OldPwd.Text == "")
            {
                MessageBox.Show("Please input old password()");
                return;
            }
            if (txt_NewPwd.Text == null || txt_NewPwd.Text == "")
            {
                MessageBox.Show("Please input new password()");
                return;
            }
            if (txt_Repeat.Text == null || txt_Repeat.Text == "")
            {
                MessageBox.Show("Please input check password()");
                return;
            }
            if (txt_Repeat.Text != txt_NewPwd.Text)
            {
                MessageBox.Show("Two passwords are different, please check again.(，)");
                return;
            }

            NET_USER_INFO_NEW userInfo = new NET_USER_INFO_NEW();
            userInfo.dwSize = (uint)Marshal.SizeOf(typeof(NET_USER_INFO_NEW));
            userInfo.name = txt_Name.Text.Trim();
            userInfo.passWord = txt_OldPwd.Text.Trim();
            IntPtr inPtr = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_USER_INFO_NEW)));


            NET_USER_INFO_NEW stuModifyInfo = new NET_USER_INFO_NEW();
            stuModifyInfo.dwSize = (uint)Marshal.SizeOf(typeof(NET_USER_INFO_NEW));
            stuModifyInfo.passWord = txt_NewPwd.Text.Trim();
            IntPtr insubPtr = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_USER_INFO_NEW)));

            try
            {

                Marshal.StructureToPtr(userInfo, inPtr, true);
                Marshal.StructureToPtr(stuModifyInfo, insubPtr, true);

                bool ret = NETClient.OperateUserInfoNew(m_LoginID, EM_OPERATE_USER_TYPE.EM_OPERATE_USERINFO_TYPE_MODIFY_PWD, insubPtr, inPtr, 10000);
                if (!ret)
                {
                    MessageBox.Show(NETClient.GetLastError());
                    return;
                }
            }
            finally
            {
                Marshal.FreeHGlobal(inPtr);
                Marshal.FreeHGlobal(insubPtr);
            }
            MessageBox.Show("Modify password successfully.()");

            #endregion
        }
    }
}
