namespace AccessDemo2s
{
    partial class ChangePasswordForm
    {
        /// <summary>
        /// Required designer variable.
        /// </summary>
        private System.ComponentModel.IContainer components = null;

        /// <summary>
        /// Clean up any resources being used.
        /// </summary>
        /// <param name="disposing">true if managed resources should be disposed; otherwise, false.</param>
        protected override void Dispose(bool disposing)
        {
            if (disposing && (components != null))
            {
                components.Dispose();
            }
            base.Dispose(disposing);
        }

        #region Windows Form Designer generated code

        /// <summary>
        /// Required method for Designer support - do not modify
        /// the contents of this method with the code editor.
        /// </summary>
        private void InitializeComponent()
        {
            this.txt_Name = new System.Windows.Forms.TextBox();
            this.label1 = new System.Windows.Forms.Label();
            this.txt_OldPwd = new System.Windows.Forms.TextBox();
            this.label2 = new System.Windows.Forms.Label();
            this.txt_NewPwd = new System.Windows.Forms.TextBox();
            this.label3 = new System.Windows.Forms.Label();
            this.txt_Repeat = new System.Windows.Forms.TextBox();
            this.label4 = new System.Windows.Forms.Label();
            this.btn_Change = new System.Windows.Forms.Button();
            this.SuspendLayout();
            // 
            // txt_Name
            // 
            this.txt_Name.Location = new System.Drawing.Point(178, 25);
            this.txt_Name.Margin = new System.Windows.Forms.Padding(5);
            this.txt_Name.Name = "txt_Name";
            this.txt_Name.Size = new System.Drawing.Size(139, 23);
            this.txt_Name.TabIndex = 3;
            // 
            // label1
            // 
            this.label1.AutoSize = true;
            this.label1.Location = new System.Drawing.Point(42, 28);
            this.label1.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.label1.Name = "label1";
            this.label1.Size = new System.Drawing.Size(126, 14);
            this.label1.TabIndex = 2;
            this.label1.Text = "UserName():";
            // 
            // txt_OldPwd
            // 
            this.txt_OldPwd.Location = new System.Drawing.Point(178, 63);
            this.txt_OldPwd.Margin = new System.Windows.Forms.Padding(5);
            this.txt_OldPwd.Name = "txt_OldPwd";
            this.txt_OldPwd.Size = new System.Drawing.Size(139, 23);
            this.txt_OldPwd.TabIndex = 5;
            this.txt_OldPwd.UseSystemPasswordChar = true;
            // 
            // label2
            // 
            this.label2.AutoSize = true;
            this.label2.Location = new System.Drawing.Point(14, 66);
            this.label2.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.label2.Name = "label2";
            this.label2.Size = new System.Drawing.Size(154, 14);
            this.label2.TabIndex = 4;
            this.label2.Text = "Old Password():";
            // 
            // txt_NewPwd
            // 
            this.txt_NewPwd.Location = new System.Drawing.Point(178, 101);
            this.txt_NewPwd.Margin = new System.Windows.Forms.Padding(5);
            this.txt_NewPwd.Name = "txt_NewPwd";
            this.txt_NewPwd.Size = new System.Drawing.Size(139, 23);
            this.txt_NewPwd.TabIndex = 7;
            this.txt_NewPwd.UseSystemPasswordChar = true;
            // 
            // label3
            // 
            this.label3.AutoSize = true;
            this.label3.Location = new System.Drawing.Point(14, 104);
            this.label3.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.label3.Name = "label3";
            this.label3.Size = new System.Drawing.Size(154, 14);
            this.label3.TabIndex = 6;
            this.label3.Text = "New Password():";
            // 
            // txt_Repeat
            // 
            this.txt_Repeat.Location = new System.Drawing.Point(178, 139);
            this.txt_Repeat.Margin = new System.Windows.Forms.Padding(5);
            this.txt_Repeat.Name = "txt_Repeat";
            this.txt_Repeat.Size = new System.Drawing.Size(139, 23);
            this.txt_Repeat.TabIndex = 9;
            this.txt_Repeat.UseSystemPasswordChar = true;
            // 
            // label4
            // 
            this.label4.AutoSize = true;
            this.label4.Location = new System.Drawing.Point(42, 142);
            this.label4.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.label4.Name = "label4";
            this.label4.Size = new System.Drawing.Size(126, 14);
            this.label4.TabIndex = 8;
            this.label4.Text = "Repeat():";
            // 
            // btn_Change
            // 
            this.btn_Change.Font = new System.Drawing.Font("", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_Change.Location = new System.Drawing.Point(93, 190);
            this.btn_Change.Margin = new System.Windows.Forms.Padding(4);
            this.btn_Change.Name = "btn_Change";
            this.btn_Change.Size = new System.Drawing.Size(140, 24);
            this.btn_Change.TabIndex = 13;
            this.btn_Change.Text = "Modify()";
            this.btn_Change.UseVisualStyleBackColor = true;
            this.btn_Change.Click += new System.EventHandler(this.btn_Change_Click);
            // 
            // ChangePasswordForm
            // 
            this.AutoScaleDimensions = new System.Drawing.SizeF(7F, 14F);
            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
            this.ClientSize = new System.Drawing.Size(340, 237);
            this.Controls.Add(this.btn_Change);
            this.Controls.Add(this.txt_Repeat);
            this.Controls.Add(this.label4);
            this.Controls.Add(this.txt_NewPwd);
            this.Controls.Add(this.label3);
            this.Controls.Add(this.txt_OldPwd);
            this.Controls.Add(this.label2);
            this.Controls.Add(this.txt_Name);
            this.Controls.Add(this.label1);
            this.Font = new System.Drawing.Font("", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.FormBorderStyle = System.Windows.Forms.FormBorderStyle.FixedSingle;
            this.MaximizeBox = false;
            this.Name = "ChangePasswordForm";
            this.StartPosition = System.Windows.Forms.FormStartPosition.CenterParent;
            this.Text = "ChangePassword()";
            this.ResumeLayout(false);
            this.PerformLayout();

        }

        #endregion

        private System.Windows.Forms.TextBox txt_Name;
        private System.Windows.Forms.Label label1;
        private System.Windows.Forms.TextBox txt_OldPwd;
        private System.Windows.Forms.Label label2;
        private System.Windows.Forms.TextBox txt_NewPwd;
        private System.Windows.Forms.Label label3;
        private System.Windows.Forms.TextBox txt_Repeat;
        private System.Windows.Forms.Label label4;
        private System.Windows.Forms.Button btn_Change;
    }
}